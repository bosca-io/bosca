package bosca.workops.model.notification

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Durable hand-off for non-email WorkOps notification channels. Triggered pipelines own the
 * channel-specific endpoint and credentials; WorkOps owns recipient resolution and retry staging.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.workops.notification.channel_requested")
@Serializable
data class WorkOpsChannelRequested(
    @Contextual val deliveryId: UUID,
    val channel: NotificationChannel,
    val target: String,
    @Contextual val payload: JsonElement,
) : Event {
    override fun identityKey(): Any = deliveryId
}
