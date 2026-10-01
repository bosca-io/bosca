package bosca.ecommerce.events

import bosca.ecommerce.model.SubscriptionStatus
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Subscription lifecycle events (`bosca.ecommerce.subscription.*`), emitted from
 * `SubscriptionServiceImpl`. Renewal is a system action (no principal); the renewal sweep emits
 * [SubscriptionRenewed] on success and [SubscriptionRenewalFailed] otherwise, plus
 * [SubscriptionStatusChanged] whenever the persisted status actually moves.
 */
@Serializable
sealed class SubscriptionEvent : Event {
    @Contextual
    abstract val subscriptionId: UUID

    @Contextual
    abstract val accountId: UUID

    override fun identityKey(): Any = subscriptionId
}

const val SUBSCRIPTION_CREATED_CHANNEL = "bosca.ecommerce.subscription.created"
const val SUBSCRIPTION_STATUS_CHANGED_CHANNEL = "bosca.ecommerce.subscription.status_changed"
const val SUBSCRIPTION_RENEWED_CHANNEL = "bosca.ecommerce.subscription.renewed"
const val SUBSCRIPTION_RENEWAL_FAILED_CHANNEL = "bosca.ecommerce.subscription.renewal_failed"

@JobEvent(
    jobs = [],
    pubsubChannel = SUBSCRIPTION_CREATED_CHANNEL,
    displayName = "Subscription Created",
    description = "A subscription was started (direct signup or via cart checkout).",
)
@Serializable
data class SubscriptionCreated(
    @Contextual override val subscriptionId: UUID,
    @Contextual override val accountId: UUID,
    @Contextual val storeId: UUID,
    @Contextual val planId: UUID,
    @Contextual val planGroupId: UUID,
    @Contextual val cartId: UUID? = null,
) : SubscriptionEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = SUBSCRIPTION_STATUS_CHANGED_CHANNEL,
    displayName = "Subscription Status Changed",
    description = "A subscription transitioned to a new lifecycle status.",
)
@Serializable
data class SubscriptionStatusChanged(
    @Contextual override val subscriptionId: UUID,
    @Contextual override val accountId: UUID,
    val status: SubscriptionStatus,
    val previousStatus: SubscriptionStatus,
) : SubscriptionEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = SUBSCRIPTION_RENEWED_CHANNEL,
    displayName = "Subscription Renewed",
    description = "A subscription's renewal charge succeeded and the next billing date advanced.",
)
@Serializable
data class SubscriptionRenewed(
    @Contextual override val subscriptionId: UUID,
    @Contextual override val accountId: UUID,
    @Contextual val paymentId: UUID,
    val renewals: Int,
) : SubscriptionEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = SUBSCRIPTION_RENEWAL_FAILED_CHANNEL,
    displayName = "Subscription Renewal Failed",
    description = "A subscription renewal could not be charged (declined or no saved method on file).",
)
@Serializable
data class SubscriptionRenewalFailed(
    @Contextual override val subscriptionId: UUID,
    @Contextual override val accountId: UUID,
    val paymentFailures: Int,
) : SubscriptionEvent()
