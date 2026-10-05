package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.ProductType
import bosca.serialization.UUID

/** Persistence for `ecom.catalog_products`. */
@Repository
interface CatalogProductRepository {

    @Query("select * from ecom.catalog_products where id = :id and deleted is null")
    suspend fun get(id: UUID): CatalogProduct?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.catalog_products where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<CatalogProduct>

    @Query(
        """
        select * from ecom.catalog_products
         where catalog_id = :catalogId and deleted is null
           and (:type is null or type = (:type)::ecom.product_type)
           and (not :activeOnly or (starts <= now() and now() < ends))
         order by created offset :offset limit :limit
        """,
    )
    suspend fun getByCatalog(
        catalogId: UUID,
        type: ProductType?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
    ): List<CatalogProduct>

    @Query(
        """
        insert into ecom.catalog_products
            (catalog_id, product_id, type, price, taxable, starts, ends, promotions, extras)
        values
            (:catalogId, :productId, (:type)::ecom.product_type, :price, :taxable, :starts, :ends, :promotions, :extras::jsonb)
        returning *
        """,
    )
    suspend fun add(catalogProduct: CatalogProduct): CatalogProduct

    @Query(
        """
        update ecom.catalog_products
           set price = :price, taxable = :taxable, starts = :starts, ends = :ends,
               promotions = :promotions, extras = :extras::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(catalogProduct: CatalogProduct): CatalogProduct?

    @Query("update ecom.catalog_products set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
