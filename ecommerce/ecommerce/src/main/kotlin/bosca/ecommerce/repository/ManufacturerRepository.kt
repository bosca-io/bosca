package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Manufacturer
import bosca.serialization.UUID

/** Persistence for `ecom.manufacturers`. */
@Repository
interface ManufacturerRepository {

    @Query("select * from ecom.manufacturers where id = :id and deleted is null")
    suspend fun get(id: UUID): Manufacturer?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.manufacturers where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Manufacturer>

    @Query(
        "select * from ecom.manufacturers where company_id = :companyId and deleted is null " +
            "order by name offset :offset limit :limit",
    )
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Manufacturer>

    @Query("insert into ecom.manufacturers (company_id, name, extras) values (:companyId, :name, :extras::jsonb) returning *")
    suspend fun add(manufacturer: Manufacturer): Manufacturer

    @Query(
        """
        update ecom.manufacturers
           set name = :name, extras = :extras::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(manufacturer: Manufacturer): Manufacturer?

    @Query("update ecom.manufacturers set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
