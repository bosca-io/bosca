package bosca.ecommerce.service

import bosca.ecommerce.model.CreditCard
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PaymentResult
import bosca.ecommerce.model.SavedPaymentMethod

/**
 * Payment gateway behavior — the SPI behind a `PaymentProvider` config row, selected by [key] == the
 * row's `providerKey` (DI lookup, no `Class.forName`). [provider] carries the row's settings (jsonb
 * `configuration`; API credentials etc.).
 *
 * A charge supplies a [token] and/or a raw [creditCard], and the gateway picks what it needs:
 * token-based gateways (e.g. Stripe) take the single-use [token] (or tokenize the [creditCard]
 * first), while PAN-processing gateways (e.g. BluePay) require the raw [creditCard]. The [creditCard]
 * is transient — gateways must never persist or log the PAN; the recorded `Payment` keeps provider
 * tokens/ids only. When [save] is set, [submit] also returns a reusable [SavedPaymentMethod] in its
 * result, which [charge] later re-bills (subscription renewals). A test provider ships; Stripe/BluePay
 * are a follow-up spec.
 */
interface PaymentProcessor {

    /** DI key matched against a `PaymentProvider.providerKey`. */
    val key: String

    /**
     * Authorize + capture a payment with a [token] and/or raw [creditCard]; save the method when [save]
     * is set. [currency] is the ISO-4217 code the charge is in (the store's currency, multi-currency
     * Phase 1) — the gateway charges in it rather than a provider-config default.
     */
    suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: CreditCard?, save: Boolean, idempotencyKey: String? = null): PaymentResult

    /** Refund against an original transaction, in the original's [currency]. */
    suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String? = null): PaymentResult

    /** Re-bill a previously saved payment method (renewals), in [currency]. */
    suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String? = null): PaymentResult
}
