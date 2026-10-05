package bosca.workops.model.notification

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Self-contained email intent emitted after WorkOps notification schemes and per-event channel
 * preferences resolve an EMAIL recipient. A triggered pipeline owns rendering and delivery.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.workops.notification.email_requested")
@Serializable
data class WorkOpsEmailRequested(
    val recipientIds: Set<@Contextual UUID>,
    val event: String,
    val projectName: String,
    val entityType: String,
    @Contextual val entityId: UUID,
    val entityKey: String,
    val title: String,
    val body: String,
    val actorName: String? = null,
    val linkPath: String,
    @Contextual val deliveryId: UUID = UUID.random(),
) : Event {
    override fun identityKey(): Any = deliveryId
}
