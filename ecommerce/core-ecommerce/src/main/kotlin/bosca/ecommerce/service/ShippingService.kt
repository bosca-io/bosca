package bosca.ecommerce.service

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.ShippingRate
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Shipping rate quoting + selection. [rates] resolves the company's shipping providers (by
 * `providerKey`) and aggregates their options for the cart's destination. [selectRate] re-quotes,
 * matches the chosen [ShippingRate.token] (never trusting a client-sent amount), and sets the cart's
 * SHIPPING line.
 */
interface ShippingService : Service {

    /** Rate options for the cart (across the company's configured shipping providers). */
    suspend fun rates(cartId: UUID): List<ShippingRate>

    /** Select a quoted rate (by token) and set it as the cart's shipping line. */
    suspend fun selectRate(cartId: UUID, token: String, principalId: UUID?): Cart
}
