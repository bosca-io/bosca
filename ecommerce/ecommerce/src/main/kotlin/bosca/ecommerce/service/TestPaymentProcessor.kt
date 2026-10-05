package bosca.ecommerce.service

import bosca.ecommerce.model.CreditCard
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PaymentResult
import bosca.ecommerce.model.SavedPaymentMethod
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A test payment processor that always approves. Saving returns a fake reusable token so the
 * subscription save-then-recharge flow can be exercised end to end. Real gateways
 * (Stripe/BluePay) implement [PaymentProcessor] with a different [key] in a follow-up spec.
 */
@OptIn(ExperimentalUuidApi::class)
class TestPaymentProcessor : PaymentProcessor {

    override val key: String = "test"

    // Approves a token or a raw card alike (a real gateway would route on which is present). [currency] is ignored.
    override suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: CreditCard?, save: Boolean, idempotencyKey: String?): PaymentResult =
        PaymentResult(
            complete = true,
            confirmed = true,
            status = "approved",
            transactionId = "test-${Uuid.random()}",
            providerId = "test",
            type = "test",
            message = "approved",
            saved = if (save) SavedPaymentMethod(providerKey = key, token = "save-${token ?: Uuid.random()}") else null,
        )

    override suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String?): PaymentResult =
        PaymentResult(complete = true, confirmed = true, status = "refunded", transactionId = "test-refund-${Uuid.random()}", providerId = "test")

    override suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String?): PaymentResult =
        PaymentResult(complete = true, confirmed = true, status = "approved", transactionId = "test-charge-${Uuid.random()}", providerId = "test")
}
