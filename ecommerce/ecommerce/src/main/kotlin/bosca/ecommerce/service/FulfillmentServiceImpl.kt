package bosca.ecommerce.service

import bosca.db.transaction
import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.FulfillmentCenterInput
import bosca.ecommerce.repository.FulfillmentCenterRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.time.Duration
import org.slf4j.LoggerFactory

/** Fulfillment centers. Creation is audited; [syncInventory] is the scheduled inventory-sync sweep. */
@ServiceImplementation
class FulfillmentServiceImpl(
    private val fulfillmentCenterRepository: FulfillmentCenterRepository,
    private val inventoryService: InventoryService,
    private val auditService: EcomAuditService,
) : FulfillmentService {

    override suspend fun getCenter(id: UUID): FulfillmentCenter? = fulfillmentCenterRepository.get(id)

    override suspend fun getCentersByCompany(companyId: UUID): List<FulfillmentCenter> =
        fulfillmentCenterRepository.getByCompany(companyId)

    override suspend fun addCenter(input: FulfillmentCenterInput, principalId: UUID?): FulfillmentCenter = transaction {
        val center = fulfillmentCenterRepository.add(
            FulfillmentCenter(
                companyId = input.companyId,
                name = input.name,
                connectorKey = input.connectorKey,
                shippingProviderId = input.shippingProviderId,
                address1 = input.address1,
                address2 = input.address2,
                city = input.city,
                state = input.state,
                country = input.country,
                zip = input.zip,
            ),
        )
        auditService.record(
            entityType = "fulfillment_center",
            entityId = center.id,
            action = "created",
            serializer = FulfillmentCenter.serializer(),
            after = center,
            principalId = principalId,
        )
        center
    }

    override suspend fun editCenter(id: UUID, input: FulfillmentCenterInput, principalId: UUID?): FulfillmentCenter =
        transaction {
            val existing = fulfillmentCenterRepository.get(id) ?: error("fulfillment center $id not found")
            val updated = fulfillmentCenterRepository.update(
                existing.copy(
                    name = input.name,
                    connectorKey = input.connectorKey,
                    shippingProviderId = input.shippingProviderId,
                    address1 = input.address1,
                    address2 = input.address2,
                    city = input.city,
                    state = input.state,
                    country = input.country,
                    zip = input.zip,
                ),
            ) ?: error("fulfillment center $id not found")
            auditService.record(
                entityType = "fulfillment_center",
                entityId = id,
                action = "updated",
                serializer = FulfillmentCenter.serializer(),
                before = existing,
                after = updated,
                principalId = principalId,
            )
            updated
        }

    override suspend fun syncInventory(): Int {
        val now = OffsetDateTime.now()
        var updated = 0
        for (center in fulfillmentCenterRepository.getSyncable()) {
            if (!isDue(center, now)) continue
            val connector = connector(center.connectorKey) ?: continue
            for (inventory in inventoryService.getByCenter(center.id)) {
                if (inventory.manualQuantity) continue // operator-pinned; never connector-synced
                val reported = connector.getQuantity(center, inventory.sku) ?: continue
                if (inventoryService.syncQuantity(inventory.id, reported, principalId = null) != null) updated++
            }
            fulfillmentCenterRepository.touchSynced(center.id)
        }
        return updated
    }

    /** A center is due when it has never synced or its [FulfillmentCenter.syncIntervalSeconds] has elapsed. */
    private fun isDue(center: FulfillmentCenter, now: OffsetDateTime): Boolean {
        val last = center.lastSynced ?: return true
        return Duration.between(last, now).seconds >= center.syncIntervalSeconds
    }

    /** Resolve a center's inventory connector by DI name, or null (logged) when none is registered. */
    private suspend fun connector(key: String): InventoryConnector? =
        try {
            provide<InventoryConnector>(name = key)
        } catch (e: MissingProviderException) {
            log.warn("no inventory connector '{}' registered; skipping its centers ({})", key, e.message)
            null
        }

    private companion object {
        private val log = LoggerFactory.getLogger(FulfillmentServiceImpl::class.java)
    }
}
