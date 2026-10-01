package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/** Card brand (best-effort; gateways re-derive it from the number). */
@Serializable
enum class CreditCardType { VISA, MASTERCARD, DISCOVER, AMERICAN_EXPRESS, UNKNOWN }

/**
 * Raw card details for gateways that process a PAN directly (e.g. BluePay).
 *
 * **This is a transient payment INPUT only.** It is handed to the [bosca.ecommerce.service.PaymentProcessor]
 * for the transaction and is **NEVER** persisted to `ecom.payments` or written to the audit log — those
 * record provider tokens/ids only (see [Payment], which has no card columns). Token-based gateways
 * (e.g. Stripe) take a single-use [token] instead, or tokenize this card before charging. Never log it.
 */
@Serializable
data class CreditCard(
    val name: String,
    val number: String,
    val cvv: String,
    val type: CreditCardType = CreditCardType.UNKNOWN,
    val expirationMonth: Int,
    val expirationYear: Int,
)
