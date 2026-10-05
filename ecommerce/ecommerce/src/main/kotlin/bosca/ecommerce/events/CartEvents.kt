package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Cart lifecycle events (`bosca.ecommerce.cart.*`). Emitted from `CartServiceImpl` (created/submitted/
 * paid/expired) and `PaymentServiceImpl` (refunded/voided, when the payment is bound to a cart). Each
 * carries [storeId] as the tenant-scoping filter field; `jobs = []` because ecom enqueues no work of
 * its own — these publish to PubSub and feed the trigger/pipeline dispatcher.
 */
@Serializable
sealed class CartEvent : Event {
    @Contextual
    abstract val cartId: UUID

    @Contextual
    abstract val storeId: UUID

    override fun identityKey(): Any = cartId
}

const val CART_CREATED_CHANNEL = "bosca.ecommerce.cart.created"
const val CART_SUBMITTED_CHANNEL = "bosca.ecommerce.cart.submitted"
const val CART_PAID_CHANNEL = "bosca.ecommerce.cart.paid"
const val CART_REFUNDED_CHANNEL = "bosca.ecommerce.cart.refunded"
const val CART_VOIDED_CHANNEL = "bosca.ecommerce.cart.voided"
const val CART_EXPIRED_CHANNEL = "bosca.ecommerce.cart.expired"
const val CART_COMPLETED_CHANNEL = "bosca.ecommerce.cart.completed"

@JobEvent(
    jobs = [],
    pubsubChannel = CART_CREATED_CHANNEL,
    displayName = "Cart Created",
    description = "A shopping cart was opened in a store.",
)
@Serializable
data class CartCreated(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val companyId: UUID,
    @Contextual val accountId: UUID? = null,
    @Contextual val customerId: UUID? = null,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_SUBMITTED_CHANNEL,
    displayName = "Cart Submitted",
    description = "A cart was submitted for checkout.",
)
@Serializable
data class CartSubmitted(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val companyId: UUID,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_PAID_CHANNEL,
    displayName = "Cart Paid",
    description = "A cart was paid in full at checkout.",
)
@Serializable
data class CartPaid(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val companyId: UUID,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_REFUNDED_CHANNEL,
    displayName = "Cart Refunded",
    description = "A payment taken against a cart was refunded.",
)
@Serializable
data class CartRefunded(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_VOIDED_CHANNEL,
    displayName = "Cart Voided",
    description = "A payment taken against a cart was voided.",
)
@Serializable
data class CartVoided(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_EXPIRED_CHANNEL,
    displayName = "Cart Expired",
    description = "An open cart passed its expiration and was reclaimed by the sweep.",
)
@Serializable
data class CartExpired(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val companyId: UUID,
) : CartEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = CART_COMPLETED_CHANNEL,
    displayName = "Cart Completed",
    description = "Every shipment for a paid order shipped; the order is complete.",
)
@Serializable
data class CartCompleted(
    @Contextual override val cartId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val companyId: UUID,
) : CartEvent()
