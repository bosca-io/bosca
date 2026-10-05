package bosca.workops.model.automation

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Profile-addressed email requested by a WorkOps automation action. A triggered pipeline owns
 * rendering and preference-aware delivery; the executor only resolves recipients and emits intent.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.workops.automation.email_requested")
@Serializable
data class AutomationEmailRequested(
    val recipientIds: Set<@Contextual UUID>,
    val subject: String,
    val body: String,
    @Contextual val deliveryId: UUID = UUID.random(),
) : Event {
    override fun identityKey(): Any = deliveryId
}
