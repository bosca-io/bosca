package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * One physical-fulfillment unit of a paid order, scoped to a single fulfillment center
 * (`ecom.shipments`). Created when the cart is paid — the order's physical lines are split per center,
 * one [Shipment] each (generalizing the legacy marketplace per-vendor order split to the
 * single-merchant per-center case). The cart IS the order: there is no separate order entity, so
 * [cartId] is the order key. A shipment holds one or more [parcels] (the boxes the packer produced for
 * density); shipping it decrements every parcel's inventory rows and advances the order toward
 * `SHIPPED`/`COMPLETE`.
 *
 * No `version` column (status transitions take row locks like the rest of the module); bare legacy
 * timestamp names (`created`/`modified`/`shipped`).
 */
@BatchKey("id")
@Serializable
data class Shipment(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("fulfillment_center_id")
    val fulfillmentCenterId: UUID,
    val status: ShipmentStatus = ShipmentStatus.AWAITING,
    /** The boxes this shipment carries — each a packed container + the SKUs in it. */
    @property:DbMapper(JsonbMapper::class)
    val parcels: List<ShipmentParcel> = emptyList(),
    /**
     * Items that couldn't be packed into any container (oversize, or no container defined). Non-empty
     * means the shipment is `UNABLE_TO_PACKAGE` — there is no loose box; these must be resolved (add a
     * fitting container, then re-pack) before the shipment can ship.
     */
    @property:DbMapper(JsonbMapper::class)
    val unpacked: List<ShipmentLine> = emptyList(),
    val carrier: String? = null,
    val tracking: String? = null,
    @ColumnName("label_url")
    val labelUrl: String? = null,
    /** The carrier's own latest status wording (raw, for fidelity), set by tracking updates. */
    @ColumnName("carrier_status")
    val carrierStatus: String? = null,
    @Contextual
    val shipped: OffsetDateTime? = null,
    /** When the carrier reported delivery, if it has. */
    @Contextual
    val delivered: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * One box within a [Shipment]: the container (box type) it was packed into and the lines (SKUs +
 * quantities) inside it. A shipment can carry several — the packer sorts a center's items into boxes
 * for density and they all dispatch together under the one shipment. Stored in the
 * `ecom.shipments.parcels` jsonb array. [containerId] is null for a loose box (oversize / no catalog).
 */
@Serializable
data class ShipmentParcel(
    @Contextual
    val containerId: UUID? = null,
    val lines: List<ShipmentLine> = emptyList(),
)

/**
 * One line of a [ShipmentParcel]: the inventory row to draw down on ship and the quantity from it.
 * Derived from a paid cart line's [InventoryReservation], so shipping decrements exactly what was
 * reserved (the same `inventoryId` the cart held).
 */
@Serializable
data class ShipmentLine(
    @Contextual
    val catalogProductId: UUID,
    @Contextual
    val inventoryId: UUID,
    val sku: String,
    val quantity: Int,
)
