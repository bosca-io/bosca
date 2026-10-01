package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Catalog
import bosca.serialization.UUID

/** Persistence for `ecom.catalogs` (unique `(company_id, key)`). */
@Repository
interface CatalogRepository {

    @Query("select * from ecom.catalogs where id = :id and deleted is null")
    suspend fun get(id: UUID): Catalog?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.catalogs where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Catalog>

    @Query("select * from ecom.catalogs where company_id = :companyId and key = :key and deleted is null")
    suspend fun getByKey(companyId: UUID, key: String): Catalog?

    @Query("select * from ecom.catalogs where company_id = :companyId and deleted is null order by name")
    suspend fun getByCompany(companyId: UUID): List<Catalog>

    @Query("insert into ecom.catalogs (company_id, key, name, currency) values (:companyId, :key, :name, :currency) returning *")
    suspend fun add(catalog: Catalog): Catalog

    @Query("update ecom.catalogs set key = :key, name = :name, modified = now() where id = :id and deleted is null returning *")
    suspend fun update(catalog: Catalog): Catalog?
}
