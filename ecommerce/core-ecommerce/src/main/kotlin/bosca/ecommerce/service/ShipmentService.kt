package bosca.ecommerce.service

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentStatus
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Order fulfillment. A [Shipment] is the per-fulfillment-center physical-fulfillment
 * unit of a paid order (the cart is the order). Shipments are created when the cart is paid; Phase 2
 * adds shipping (inventory draw-down + order advance).
 */
interface ShipmentService : Service {

    suspend fun get(id: UUID): Shipment?

    /** Every shipment for an order (cart). */
    suspend fun getByCart(cartId: UUID): List<Shipment>

    /** A store's shipments, newest first — the fulfillment monitoring view. [status] null = all states. */
    suspend fun getByStore(storeId: UUID, status: ShipmentStatus?, offset: Int, limit: Int): List<Shipment>

    /**
     * Split a just-paid order into shipments: group its physical lines' inventory reservations by
     * fulfillment center, one [Shipment] per center (status `AWAITING`). Idempotent — an order already
     * split returns its existing shipments. Returns the shipments created (empty for an all-digital
     * order, which has nothing to ship).
     */
    suspend fun createForPaidCart(cart: Cart, principalId: UUID?): List<Shipment>

    /**
     * Ship an `AWAITING` shipment: draw down its lines' inventory (on-hand + pending decrement, the
     * legacy `shipped` semantics), mark it `SHIPPED` with [carrier]/[tracking], and emit
     * `ShipmentShipped`. Advancing the order to `SHIPPED`/`COMPLETE` is the caller's next step
     * ([CartService.advanceFulfillment]).
     */
    suspend fun ship(shipmentId: UUID, carrier: String?, tracking: String?, principalId: UUID?): Shipment

    /**
     * Re-run the packer on an `AWAITING` or `UNABLE_TO_PACKAGE` shipment with the company's current
     * containers and rebuild its parcels/unpacked — so a shipment blocked for lack of a fitting
     * container picks up one added later (becoming `AWAITING`). Returns the re-packed shipment.
     */
    suspend fun repack(shipmentId: UUID, principalId: UUID?): Shipment

    /**
     * Re-pack every `UNABLE_TO_PACKAGE` shipment in a company — used when a container is added/edited so
     * blocked orders box up without manual intervention. Returns the count re-packed.
     */
    suspend fun repackUnpackable(companyId: UUID, principalId: UUID?): Int

    /**
     * Poll the shipment's carrier (its fulfillment center's shipping provider) for the latest tracking
     * reading and apply it — advancing `status` along the lifecycle (only ever forward), recording the
     * carrier's raw status and a delivery timestamp. A no-op when there's no update or no provider.
     * Returns the (possibly unchanged) shipment.
     */
    suspend fun refreshTracking(shipmentId: UUID, principalId: UUID?): Shipment

    /**
     * Poll tracking for every in-flight shipment (the runner sweep): refreshes each dispatched,
     * not-yet-delivered shipment. Returns how many were polled.
     */
    suspend fun sweepTracking(): Int
}
