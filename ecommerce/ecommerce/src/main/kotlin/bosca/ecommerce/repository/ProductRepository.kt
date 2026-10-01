package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Product
import bosca.serialization.UUID

/** Persistence for `ecom.products` (unique `(manufacturer_id, manufacturer_sku)` and `metadata_id`). */
@Repository
interface ProductRepository {

    @Query("select * from ecom.products where id = :id and deleted is null")
    suspend fun get(id: UUID): Product?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.products where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Product>

    @Query("select * from ecom.products where metadata_id = :metadataId and deleted is null")
    suspend fun getByMetadataId(metadataId: UUID): Product?

    @Query("select * from ecom.products where company_id = :companyId and deleted is null order by created offset :offset limit :limit")
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Product>

    @Query(
        """
        insert into ecom.products (company_id, manufacturer_id, manufacturer_sku, metadata_id, metadata_version, type, configuration, weight, width, height, length)
        values (:companyId, :manufacturerId, :manufacturerSku, :metadataId, :metadataVersion, (:type)::ecom.product_type, :configuration::jsonb, :weight, :width, :height, :length)
        returning *
        """,
    )
    suspend fun add(product: Product): Product

    @Query(
        """
        update ecom.products
           set manufacturer_id = :manufacturerId, manufacturer_sku = :manufacturerSku,
               type = (:type)::ecom.product_type, configuration = :configuration::jsonb, weight = :weight,
               width = :width, height = :height, length = :length, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(product: Product): Product?

    /** Advance the pinned content version (the content-publish reaction). */
    @Query("update ecom.products set metadata_version = :metadataVersion, modified = now() where id = :id and deleted is null returning *")
    suspend fun updatePin(id: UUID, metadataVersion: Int): Product?

    @Query("update ecom.products set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
