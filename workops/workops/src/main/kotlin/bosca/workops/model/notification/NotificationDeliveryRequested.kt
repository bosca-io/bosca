package bosca.workops.model.notification

import bosca.events.Event
import bosca.events.annotation.JobEvent
import kotlinx.serialization.Serializable

/** Emits a typed WorkOps notification that is not already represented by a richer domain event. */
@JobEvent(
    jobs = [NotificationDeliveryJob::class],
    pubsubChannel = "bosca.workops.notification.delivery_requested",
)
@Serializable
data class NotificationDeliveryRequested(
    val delivery: NotificationDelivery,
) : Event {
    override fun identityKey(): Any = delivery.id
}
