package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.ecommerce.repository.StoreRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Stores. Create/edit are audited; the catalog/provider/shipping-product FKs are DB-enforced. */
@ServiceImplementation
class StoreServiceImpl(
    private val storeRepository: StoreRepository,
    private val auditService: EcomAuditService,
) : StoreService {

    override suspend fun get(id: UUID): Store? = storeRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Store> = storeRepository.getByIds(ids)

    override suspend fun getByIdentifier(identifier: String): Store? = storeRepository.getByIdentifier(identifier)

    override suspend fun getByCompany(companyId: UUID): List<Store> = storeRepository.getByCompany(companyId)

    override suspend fun create(input: StoreInput, principalId: UUID?): Store = transaction {
        val store = storeRepository.add(
            Store(
                identifier = input.identifier,
                name = input.name,
                companyId = input.companyId,
                catalogId = input.catalogId,
                type = input.type,
                paymentProviderId = input.paymentProviderId,
                shippingCatalogProductId = input.shippingCatalogProductId,
                cartExpirationSeconds = input.cartExpirationSeconds,
            ),
        )
        auditService.record(
            entityType = "store",
            entityId = store.id,
            action = "created",
            serializer = Store.serializer(),
            after = store,
            principalId = principalId,
            storeId = store.id,
        )
        store
    }

    override suspend fun edit(id: UUID, input: StoreInput, principalId: UUID?): Store = transaction {
        val existing = storeRepository.get(id) ?: error("store $id not found")
        val updated = storeRepository.update(
            existing.copy(
                identifier = input.identifier,
                name = input.name,
                catalogId = input.catalogId,
                type = input.type,
                paymentProviderId = input.paymentProviderId,
                shippingCatalogProductId = input.shippingCatalogProductId,
                cartExpirationSeconds = input.cartExpirationSeconds,
            ),
        ) ?: error("store $id not found")
        auditService.record(
            entityType = "store",
            entityId = id,
            action = "updated",
            serializer = Store.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
            storeId = id,
        )
        updated
    }
}
