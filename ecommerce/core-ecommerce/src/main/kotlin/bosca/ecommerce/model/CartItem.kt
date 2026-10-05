package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * One line in a cart, stored inside the `ecom.carts.items` jsonb array. Prices snapshot the catalog
 * price at add-time; the pricing pipeline refines [salesPrice]/[discounts]/[taxes] and
 * the `*Subtotal`s during whole-cart recomputation. [reservations] records which inventory rows this
 * line is holding (in_cart), so the expiration sweep and item removal can release exactly those
 * holds. [parentId] links a dependent line (e.g. a shipping line) to its parent product line.
 *
 * Promotion fields (`pricingModifiedBy`, `discountsFrom`, applied promotions) carry promotion pricing adjustments.
 */
@Serializable
data class CartItem(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    val catalogProductId: UUID,
    val type: ProductType,
    val quantity: Int,
    val status: CartStatus = CartStatus.OPEN,
    val baseRetailPrice: Money = Money.ZERO,
    val retailPrice: Money = Money.ZERO,
    val salesPrice: Money = Money.ZERO,
    val retailSubtotal: Money = Money.ZERO,
    val salesSubtotal: Money = Money.ZERO,
    val discounts: Money = Money.ZERO,
    val paid: Money = Money.ZERO,
    val taxes: Tax = Tax.ZERO,
    @Contextual
    val expires: OffsetDateTime? = null,
    @Contextual
    val parentId: UUID? = null,
    val configuration: CartItemConfiguration = EmptyCartItemConfiguration,
    val reservations: List<InventoryReservation> = emptyList(),
)

/** A hold this cart line placed against one inventory row, released on removal/expiration. */
@Serializable
data class InventoryReservation(
    @Contextual
    val inventoryId: UUID,
    val quantity: Int,
)
