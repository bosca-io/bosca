package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Shipment
import bosca.serialization.UUID

/** Persistence for `ecom.shipments` (`lines` jsonb via JsonbMapper; `status` is the native `ecom.shipment_status` enum). */
@Repository
interface ShipmentRepository {

    @Query("select * from ecom.shipments where id = :id")
    suspend fun get(id: UUID): Shipment?

    @Query("select * from ecom.shipments where id = :id for update")
    suspend fun getForUpdate(id: UUID): Shipment?

    /** Every shipment for an order (cart), oldest first — used to decide when the whole order has shipped. */
    @Query("select * from ecom.shipments where cart_id = :cartId order by created")
    suspend fun getByCart(cartId: UUID): List<Shipment>

    @Query("select * from ecom.shipments where store_id = :storeId order by created desc offset :offset limit :limit")
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Shipment>

    /** In-flight shipments (dispatched but not yet delivered/terminal) — the tracking-poll work set. */
    @Query(
        "select * from ecom.shipments where status = any(array['shipped','in_transit','out_for_delivery']::ecom.shipment_status[]) order by modified limit :limit",
    )
    suspend fun getInFlight(limit: Int): List<Shipment>

    @Query(
        "select * from ecom.shipments where store_id = :storeId and status = (:status)::ecom.shipment_status order by created desc offset :offset limit :limit",
    )
    suspend fun getByStoreAndStatus(storeId: UUID, status: String, offset: Int, limit: Int): List<Shipment>

    @Query(
        """
        insert into ecom.shipments
            (cart_id, store_id, company_id, fulfillment_center_id, status, parcels, unpacked, carrier, tracking, shipped)
        values
            (:cartId, :storeId, :companyId, :fulfillmentCenterId, (:status)::ecom.shipment_status, :parcels, :unpacked, :carrier, :tracking, :shipped)
        returning *
        """,
    )
    suspend fun add(shipment: Shipment): Shipment

    @Query(
        """
        update ecom.shipments
           set status = (:status)::ecom.shipment_status, parcels = :parcels, unpacked = :unpacked, carrier = :carrier,
               tracking = :tracking, label_url = :labelUrl, carrier_status = :carrierStatus,
               shipped = :shipped, delivered = :delivered, modified = now()
         where id = :id
        returning *
        """,
    )
    suspend fun update(shipment: Shipment): Shipment?

    /** A company's shipments blocked as UNABLE_TO_PACKAGE — the re-pack work set when containers change. */
    @Query("select * from ecom.shipments where company_id = :companyId and status = 'unable_to_package'::ecom.shipment_status order by created")
    suspend fun getUnpackableByCompany(companyId: UUID): List<Shipment>
}
