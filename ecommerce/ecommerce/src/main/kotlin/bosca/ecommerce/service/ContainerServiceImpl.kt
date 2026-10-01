package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.Container
import bosca.ecommerce.model.ContainerInput
import bosca.ecommerce.repository.ContainerRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Container (box catalog) administration. Every mutation is audited (`shipping_container`). */
@ServiceImplementation
class ContainerServiceImpl(
    private val containerRepository: ContainerRepository,
    private val shipmentService: ShipmentService,
    private val auditService: EcomAuditService,
) : ContainerService {

    override suspend fun get(id: UUID): Container? = containerRepository.get(id)

    override suspend fun getByCompany(companyId: UUID): List<Container> = containerRepository.getByCompany(companyId)

    override suspend fun add(input: ContainerInput, principalId: UUID?): Container = transaction {
        val container = containerRepository.add(input.toContainer())
        audit(container, "created", principalId)
        // A new box type can rescue awaiting orders that fell back to a loose box — re-pack them.
        shipmentService.repackUnpackable(container.companyId, principalId)
        container
    }

    override suspend fun edit(id: UUID, input: ContainerInput, principalId: UUID?): Container = transaction {
        val existing = containerRepository.get(id) ?: error("container $id not found")
        val updated = containerRepository.update(input.toContainer().copy(id = id))
            ?: error("container $id not found")
        audit(updated, "updated", principalId, before = existing)
        // Changed dimensions/capacity can now fit a previously-loose order — re-pack the loose ones.
        shipmentService.repackUnpackable(updated.companyId, principalId)
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = containerRepository.get(id) ?: return@transaction false
        containerRepository.softDelete(id)
        audit(existing, "deleted", principalId)
        true
    }

    private fun ContainerInput.toContainer() = Container(
        companyId = companyId, name = name, width = width, height = height, length = length, weight = weight,
        supportedWidth = supportedWidth, supportedHeight = supportedHeight,
        supportedLength = supportedLength, supportedWeight = supportedWeight,
    )

    private suspend fun audit(container: Container, action: String, principalId: UUID?, before: Container? = null) {
        auditService.record(
            entityType = "shipping_container", entityId = container.id, action = action,
            serializer = Container.serializer(),
            before = before,
            after = container,
            principalId = principalId,
        )
    }
}
