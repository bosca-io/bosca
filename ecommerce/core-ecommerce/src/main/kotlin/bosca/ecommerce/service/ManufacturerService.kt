package bosca.ecommerce.service

import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.serialization.UUID
import bosca.service.Service

/** Manufacturers (product makers/brands). */
interface ManufacturerService : Service {

    /** A manufacturer by id. */
    suspend fun get(id: UUID): Manufacturer?

    /** Manufacturers by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<Manufacturer>

    /** A company's manufacturers, paged. */
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Manufacturer>

    /** Create a manufacturer. */
    suspend fun create(input: ManufacturerInput, principalId: UUID?): Manufacturer

    /** Edit a manufacturer's name and extras. */
    suspend fun update(id: UUID, input: ManufacturerInput, principalId: UUID?): Manufacturer

    /** Soft-delete a manufacturer. Returns false if it does not exist (or is already deleted). */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean
}
