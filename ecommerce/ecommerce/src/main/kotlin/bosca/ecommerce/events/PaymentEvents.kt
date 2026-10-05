package bosca.ecommerce.events

import bosca.ecommerce.model.Money
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Payment lifecycle events (`bosca.ecommerce.payment.*`), emitted from `PaymentServiceImpl`. [amount]
 * is the movement's value (the [Money] serializer is compiled in, so encoding is native-safe). A
 * refund carries [parentId] — the original payment it reverses.
 */
@Serializable
sealed class PaymentEvent : Event {
    @Contextual
    abstract val paymentId: UUID

    @Contextual
    abstract val storeId: UUID

    override fun identityKey(): Any = paymentId
}

const val PAYMENT_CONFIRMED_CHANNEL = "bosca.ecommerce.payment.confirmed"
const val PAYMENT_VOIDED_CHANNEL = "bosca.ecommerce.payment.voided"
const val PAYMENT_REFUNDED_CHANNEL = "bosca.ecommerce.payment.refunded"

@JobEvent(
    jobs = [],
    pubsubChannel = PAYMENT_CONFIRMED_CHANNEL,
    displayName = "Payment Confirmed",
    description = "A charge completed successfully (a cart checkout or a subscription renewal).",
)
@Serializable
data class PaymentConfirmed(
    @Contextual override val paymentId: UUID,
    @Contextual override val storeId: UUID,
    val amount: Money,
    @Contextual val cartId: UUID? = null,
    @Contextual val subscriptionId: UUID? = null,
) : PaymentEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = PAYMENT_VOIDED_CHANNEL,
    displayName = "Payment Voided",
    description = "A payment was voided before settlement.",
)
@Serializable
data class PaymentVoided(
    @Contextual override val paymentId: UUID,
    @Contextual override val storeId: UUID,
) : PaymentEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = PAYMENT_REFUNDED_CHANNEL,
    displayName = "Payment Refunded",
    description = "A payment was refunded (to the original tender or to account credit).",
)
@Serializable
data class PaymentRefunded(
    @Contextual override val paymentId: UUID,
    @Contextual override val storeId: UUID,
    val amount: Money,
    @Contextual val parentId: UUID,
    @Contextual val cartId: UUID? = null,
) : PaymentEvent()
