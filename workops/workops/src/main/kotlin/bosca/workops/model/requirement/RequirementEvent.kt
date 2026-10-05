package bosca.workops.model.requirement

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryJob
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

private fun validateRequirementDelivery(
    notificationEvent: NotificationEvent,
    delivery: NotificationDelivery,
    expectedDelivery: NotificationDelivery,
) {
    require(notificationEvent == NotificationEvent.REQUIREMENT_COMMENTED) {
        "Requirement comment event notification type must be ${NotificationEvent.REQUIREMENT_COMMENTED}"
    }
    require(delivery == expectedDelivery) { "Requirement event delivery must match its event fields" }
}

@Serializable
sealed class RequirementEvent : Event {
    @Contextual
    abstract val requirementId: UUID
    abstract val notificationEvent: NotificationEvent
    abstract val delivery: NotificationDelivery

    override fun identityKey(): Any = requirementId
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.requirement.commented")
@Serializable
data class RequirementCommented(
    @Contextual override val requirementId: UUID,
    val commentId: Long,
    @Contextual val profileId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.REQUIREMENT_COMMENTED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        requirementId = requirementId,
        actorProfileId = profileId,
        commentId = commentId,
    ),
) : RequirementEvent() {
    init {
        validateRequirementDelivery(
            notificationEvent,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.REQUIREMENT_COMMENTED,
                requirementId = requirementId,
                actorProfileId = profileId,
                commentId = commentId,
            ),
        )
    }
}
