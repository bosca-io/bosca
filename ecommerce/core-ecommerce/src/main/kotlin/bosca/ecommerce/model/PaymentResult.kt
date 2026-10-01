package bosca.ecommerce.model

/**
 * The gateway's response from a [bosca.ecommerce.service.PaymentProcessor] operation. Tokens/ids
 * only — never card data. [complete] = the money moved; [saved] is set when the processor stored a
 * reusable payment method (for subscription renewals). These map onto the `Payment`
 * `provider*` columns.
 */
data class PaymentResult(
    val complete: Boolean,
    val confirmed: Boolean = complete,
    val status: String? = null,
    val transactionId: String? = null,
    val providerId: String? = null,
    val avs: String? = null,
    val type: String? = null,
    val message: String? = null,
    val error: String? = null,
    /**
     * The call failed at the **transport** layer (HTTP 4xx/5xx, timeout, or an unparseable body) rather
     * than the card being declined. Callers that dun on failure (subscription renewal) must NOT count a
     * transport failure toward the dunning cap — a gateway outage is not a customer decline.
     */
    val transportFailure: Boolean = false,
    val saved: SavedPaymentMethod? = null,
)
