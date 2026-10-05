package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Stock of one product at one fulfillment center, with the reservation state machine:
 * available -> [inCart] (held by a cart) -> [pending] (paid, awaiting shipment) -> shipped (the
 * [quantity] decrements). [available] is derived, never stored. All transitions run under
 * `select … for update` row locking. [expirationSeconds] is the in-cart reservation TTL hint
 * (the cart-expiration sweep releases expired holds).
 */
@BatchKey("id")
@Serializable
data class Inventory(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("product_id")
    val productId: UUID,
    @Contextual
    @ColumnName("fulfillment_center_id")
    val fulfillmentCenterId: UUID,
    val sku: String,
    val quantity: Int = 0,
    val pending: Int = 0,
    @ColumnName("in_cart")
    val inCart: Int = 0,
    @ColumnName("manual_quantity")
    val manualQuantity: Boolean = false,
    @ColumnName("expiration_seconds")
    val expirationSeconds: Int = 86400,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
) {
    /** Units actually sellable right now: `quantity - pending - inCart`. */
    val available: Int get() = quantity - pending - inCart
}
