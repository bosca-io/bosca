package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.FulfillmentCenter
import bosca.serialization.UUID

/** Persistence for `ecom.fulfillment_centers`. */
@Repository
interface FulfillmentCenterRepository {

    @Query("select * from ecom.fulfillment_centers where id = :id and deleted is null")
    suspend fun get(id: UUID): FulfillmentCenter?

    @Query("select * from ecom.fulfillment_centers where company_id = :companyId and deleted is null order by name")
    suspend fun getByCompany(companyId: UUID): List<FulfillmentCenter>

    /** Live centers with an external connector (everything but the reserved "manual" key) — the sync sweep's working set. */
    @Query("select * from ecom.fulfillment_centers where deleted is null and connector_key <> 'manual' order by created")
    suspend fun getSyncable(): List<FulfillmentCenter>

    /** Stamp a center as just-synced (used by the sweep to honor each center's sync interval). */
    @Query("update ecom.fulfillment_centers set last_synced = now() where id = :id", returnUpdateCount = true)
    suspend fun touchSynced(id: UUID): Int

    /** Live fulfillment centers still bound to a shipping provider — guards provider deletion. */
    @Query("select count(*) from ecom.fulfillment_centers where shipping_provider_id = :id and deleted is null")
    suspend fun countByShippingProvider(id: UUID): Long

    @Query(
        """
        insert into ecom.fulfillment_centers
            (company_id, name, connector_key, shipping_provider_id, address1, address2, city, state, country, zip, sync_interval_seconds)
        values
            (:companyId, :name, :connectorKey, :shippingProviderId, :address1, :address2, :city, :state, :country, :zip, :syncIntervalSeconds)
        returning *
        """,
    )
    suspend fun add(center: FulfillmentCenter): FulfillmentCenter

    @Query(
        """
        update ecom.fulfillment_centers
           set name = :name, connector_key = :connectorKey, shipping_provider_id = :shippingProviderId,
               address1 = :address1, address2 = :address2, city = :city, state = :state,
               country = :country, zip = :zip, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(center: FulfillmentCenter): FulfillmentCenter?
}
