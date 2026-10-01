package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Charges a payment. [token] is a single-use client-side payment token (never a PAN). [save] asks
 * the processor to also store a reusable method (for subscription renewals). The store's configured
 * payment provider determines the gateway.
 */
@Serializable
data class ChargePaymentInput(
    @Contextual
    val storeId: UUID,
    val amount: Money,
    val token: String? = null,
    val type: PaymentType = PaymentType.CREDIT_CARD,
    @Contextual
    val accountId: UUID? = null,
    @Contextual
    val customerId: UUID? = null,
    @Contextual
    val cartId: UUID? = null,
    val save: Boolean = false,
    val email: String? = null,
    /** Raw card for PAN-processing gateways (e.g. BluePay) when no [token] is supplied. Never persisted. */
    val creditCard: CreditCard? = null,
    /** The check number, for [PaymentType.CHECK]. */
    val checkNumber: String? = null,
    /** The redeemable company-credit number, for [PaymentType.COMPANY_CREDIT]. */
    val companyCreditNumber: String? = null,
    /** Stable idempotency token for the gateway (e.g. `cart:<cartId>:<paid>:<amount>`) so a retried/double-clicked submit can't double-charge. */
    val idempotencyKey: String? = null,
    /** Settlement currency snapshotted from `cart.currency`; when null, [PaymentService] derives it from the store catalog. */
    val currency: String? = null,
)

/** Re-bills a previously saved payment method (subscription renewals). */
@Serializable
data class SavedChargeInput(
    @Contextual
    val storeId: UUID,
    val saved: SavedPaymentMethod,
    val amount: Money,
    @Contextual
    val accountId: UUID? = null,
    @Contextual
    val customerId: UUID? = null,
    @Contextual
    val subscriptionId: UUID? = null,
    /** Stable idempotency token for the gateway (e.g. `renewal:<subId>:<period>`) so a redelivered renewal can't double-charge. */
    val idempotencyKey: String? = null,
    /** Settlement currency snapshotted from `Subscription.currency`; when null, [PaymentService] derives it from the store catalog. */
    val currency: String? = null,
)

/** A charge result: the recorded [payment] and, when saving was requested, the reusable method. */
@Serializable
data class PaymentSubmitResult(
    val payment: Payment,
    val saved: SavedPaymentMethod? = null,
    /** The gateway call failed at the transport layer (not a decline) — renewal dunning must not count it. */
    val transportFailure: Boolean = false,
)
