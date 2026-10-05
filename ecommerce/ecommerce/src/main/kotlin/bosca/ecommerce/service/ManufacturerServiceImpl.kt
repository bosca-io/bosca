package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.ecommerce.repository.ManufacturerRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Manufacturers. Create is audited; [ManufacturerInput.extras] is stored as `jsonb`. */
@ServiceImplementation
class ManufacturerServiceImpl(
    private val manufacturerRepository: ManufacturerRepository,
    private val auditService: EcomAuditService,
) : ManufacturerService {

    override suspend fun get(id: UUID): Manufacturer? = manufacturerRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Manufacturer> = manufacturerRepository.getByIds(ids)

    override suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Manufacturer> =
        manufacturerRepository.getByCompany(companyId, offset, limit)

    override suspend fun create(input: ManufacturerInput, principalId: UUID?): Manufacturer = transaction {
        val manufacturer = manufacturerRepository.add(
            Manufacturer(
                companyId = input.companyId,
                name = input.name,
                extras = input.extras ?: EmptyManufacturerExtras,
            ),
        )
        auditService.record(
            entityType = "manufacturer",
            entityId = manufacturer.id,
            action = "created",
            serializer = Manufacturer.serializer(),
            after = manufacturer,
            principalId = principalId,
        )
        manufacturer
    }

    override suspend fun update(id: UUID, input: ManufacturerInput, principalId: UUID?): Manufacturer = transaction {
        val existing = manufacturerRepository.get(id) ?: error("manufacturer $id not found")
        val updated = manufacturerRepository.update(
            existing.copy(
                name = input.name,
                extras = input.extras ?: EmptyManufacturerExtras,
            ),
        ) ?: error("manufacturer $id not found")
        auditService.record(
            entityType = "manufacturer",
            entityId = id,
            action = "updated",
            serializer = Manufacturer.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = manufacturerRepository.get(id) ?: return@transaction false
        manufacturerRepository.softDelete(id)
        auditService.record(
            entityType = "manufacturer",
            entityId = id,
            action = "deleted",
            serializer = Manufacturer.serializer(),
            before = existing,
            principalId = principalId,
        )
        true
    }
}
