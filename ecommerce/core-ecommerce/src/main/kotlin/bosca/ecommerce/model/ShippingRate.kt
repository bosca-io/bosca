package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * One shipping rate option returned by a shipping provider for a cart. The opaque [token]
 * identifies the rate so the customer can select it (`selectShippingRate`); the chosen option's
 * [amount] becomes the price of the SHIPPING cart line.
 */
@Serializable
data class ShippingRate(
    /** Carrier name (e.g. USPS). */
    val carrier: String,
    /** Service level (e.g. Priority). */
    val serviceLevel: String,
    /** Carrier's delivery-time description, if provided. */
    val durationTerms: String? = null,
    /** Opaque token identifying this rate; passed back to select it. */
    val token: String,
    val amount: Money,
)
