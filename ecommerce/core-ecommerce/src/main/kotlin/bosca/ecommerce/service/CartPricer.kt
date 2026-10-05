package bosca.ecommerce.service

import bosca.ecommerce.model.Cart

/**
 * A step in the cart pricing pipeline. The cart service runs every registered pricer (ascending
 * [order]) on each reprice, then recomputes totals. Pricers adjust line prices/discounts/taxes; they
 * MUST be idempotent (reprice runs on every mutation) — derive from the cart's base state rather than
 * accumulating. Promotions and tax/shipping are the implementations.
 */
interface CartPricer {

    /** Lower runs first. Promotions price before tax (which is computed on the discounted amount). */
    val order: Int get() = 0

    /** Return the cart with this pricer's adjustments applied to its line items. */
    suspend fun price(cart: Cart): Cart
}
