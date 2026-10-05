package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Store
import bosca.serialization.UUID

/** Persistence for `ecom.stores` (unique `identifier`). */
@Repository
interface StoreRepository {

    @Query("select * from ecom.stores where id = :id and deleted is null")
    suspend fun get(id: UUID): Store?

    /** Batch load by id — backs the DataLoader path for nested resolvers. */
    @Query("select * from ecom.stores where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Store>

    @Query("select * from ecom.stores where identifier = :identifier and deleted is null")
    suspend fun getByIdentifier(identifier: String): Store?

    @Query("select * from ecom.stores where company_id = :companyId and deleted is null order by name")
    suspend fun getByCompany(companyId: UUID): List<Store>

    /** Live stores still bound to a payment provider — guards provider deletion from orphaning a store. */
    @Query("select count(*) from ecom.stores where payment_provider_id = :id and deleted is null")
    suspend fun countByPaymentProvider(id: UUID): Long

    @Query(
        """
        insert into ecom.stores
            (identifier, name, company_id, catalog_id, type, payment_provider_id, shipping_catalog_product_id, cart_expiration_seconds)
        values
            (:identifier, :name, :companyId, :catalogId, (:type)::ecom.store_type, :paymentProviderId, :shippingCatalogProductId, :cartExpirationSeconds)
        returning *
        """,
    )
    suspend fun add(store: Store): Store

    @Query(
        """
        update ecom.stores
           set identifier = :identifier, name = :name, catalog_id = :catalogId, type = (:type)::ecom.store_type,
               payment_provider_id = :paymentProviderId, shipping_catalog_product_id = :shippingCatalogProductId,
               cart_expiration_seconds = :cartExpirationSeconds, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(store: Store): Store?
}
