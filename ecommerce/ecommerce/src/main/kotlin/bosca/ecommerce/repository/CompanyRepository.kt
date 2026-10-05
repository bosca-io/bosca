package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.WeightUnit
import bosca.serialization.UUID

/** Persistence for `ecom.companies`. */
@Repository
interface CompanyRepository {

    @Query("select * from ecom.companies where id = :id and deleted is null")
    suspend fun get(id: UUID): Company?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.companies where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Company>

    @Query("select * from ecom.companies where deleted is null order by created offset :offset limit :limit")
    suspend fun getAll(offset: Int, limit: Int): List<Company>

    @Query("insert into ecom.companies (organization_id, profile_id) values (:organizationId, :profileId) returning *")
    suspend fun add(company: Company): Company

    @Query(
        "update ecom.companies set length_unit = (:lengthUnit)::ecom.length_unit, weight_unit = (:weightUnit)::ecom.weight_unit, modified = now() where id = :id and deleted is null returning *",
    )
    suspend fun updateUnits(id: UUID, lengthUnit: LengthUnit, weightUnit: WeightUnit): Company?

    @Query("update ecom.companies set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
