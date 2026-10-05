package bosca.ecommerce.model

import bosca.db.annotation.DbMapper
import kotlinx.serialization.Serializable

/**
 * The combinable cart lifecycle flags, ported from the legacy `bosca.cart.CartStatuses` bitmask.
 * A cart holds a SET of these at once (e.g. PREPARING + PAID). Exposed in GraphQL as
 * `[CartStatusFlag!]!`; persisted as the [CartStatus] int bitmask.
 *
 * [bit] values are stable and MUST NOT be renumbered — they are persisted.
 */
@Serializable
enum class CartStatusFlag(val bit: Int) {
    OPEN(1),
    PENDING(1 shl 1),
    PAYMENT_DUE(1 shl 2),
    PAYMENT_PENDING(1 shl 3),
    PAYMENT_FAILED(1 shl 4),
    PAID(1 shl 5),
    REFUND_DUE(1 shl 6),
    REFUND_PENDING(1 shl 7),
    REFUND_FAILED(1 shl 8),
    REFUNDED(1 shl 9),
    SHIPPING(1 shl 10),
    SHIPPED(1 shl 11),
    RETURNING(1 shl 12),
    RETURNED(1 shl 13),
    CANCELLED(1 shl 14),
    COMPLETE(1 shl 15),
    LOCKED(1 shl 16),
    EXPIRED(1 shl 17),
    VOID_FAILED(1 shl 18),
    PREPARING(1 shl 19),
}

/**
 * A cart's lifecycle as a type-safe bitmask. Wraps the raw int [mask] persisted in
 * `ecom.carts.status` (and embedded in cart-item jsonb) with zero runtime overhead — the column
 * stays an int, but the Kotlin code never juggles raw masks. Bound to the int column via
 * `@DbMapper(CartStatusMapper)`; serialized as the underlying int inside jsonb.
 */
@Serializable
@DbMapper(CartStatusMapper::class)
@JvmInline
value class CartStatus(val mask: Int) {

    /** True if every bit of [flag] is set. */
    fun has(flag: CartStatusFlag): Boolean = mask and flag.bit == flag.bit

    /** This status with [flag] added. */
    operator fun plus(flag: CartStatusFlag): CartStatus = CartStatus(mask or flag.bit)

    /** This status with [flag] cleared. */
    operator fun minus(flag: CartStatusFlag): CartStatus = CartStatus(mask and flag.bit.inv())

    /** The decoded set of flags, for GraphQL exposure as `[CartStatusFlag!]!`. */
    val flags: Set<CartStatusFlag> get() = CartStatusFlag.entries.filterTo(LinkedHashSet()) { has(it) }

    companion object {
        /** A freshly opened cart/line. */
        val OPEN: CartStatus = CartStatus(CartStatusFlag.OPEN.bit)

        /** Build a status from explicit flags. */
        fun of(vararg flags: CartStatusFlag): CartStatus = CartStatus(flags.fold(0) { acc, flag -> acc or flag.bit })
    }
}
