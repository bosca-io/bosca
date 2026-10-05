package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Return / RMA lifecycle events (`bosca.ecommerce.return.*`). Emitted when a buyer requests a return
 * and when one is refunded. Each carries [storeId] as the tenant-scoping filter; `jobs = []` because
 * ecom enqueues no work of its own — these publish to PubSub and feed the trigger/pipeline dispatcher.
 */
@Serializable
sealed class ReturnEvent : Event {
    @Contextual
    abstract val returnId: UUID

    @Contextual
    abstract val storeId: UUID

    override fun identityKey(): Any = returnId
}

const val RETURN_REQUESTED_CHANNEL = "bosca.ecommerce.return.requested"
const val RETURN_REFUNDED_CHANNEL = "bosca.ecommerce.return.refunded"

@JobEvent(
    jobs = [],
    pubsubChannel = RETURN_REQUESTED_CHANNEL,
    displayName = "Return Requested",
    description = "A buyer (or admin) opened a return/RMA against a paid order.",
)
@Serializable
data class ReturnRequested(
    @Contextual override val returnId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val cartId: UUID,
    @Contextual val companyId: UUID,
) : ReturnEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = RETURN_REFUNDED_CHANNEL,
    displayName = "Return Refunded",
    description = "A received return was restocked and refunded.",
)
@Serializable
data class ReturnRefunded(
    @Contextual override val returnId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val cartId: UUID,
    @Contextual val companyId: UUID,
) : ReturnEvent()
