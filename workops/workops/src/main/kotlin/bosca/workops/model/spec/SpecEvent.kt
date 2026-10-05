package bosca.workops.model.spec

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import bosca.workops.jobs.SpecContextSyncJob
import bosca.workops.jobs.SpecIndexJob
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryJob
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

private fun validateSpecDelivery(
    notificationEvent: NotificationEvent,
    expectedEvent: NotificationEvent,
    delivery: NotificationDelivery,
    expectedDelivery: NotificationDelivery,
) {
    require(notificationEvent == expectedEvent) { "Spec event notification type must be $expectedEvent" }
    require(delivery == expectedDelivery) { "Spec event delivery must match its event fields" }
}

@Serializable
sealed class SpecEvent : Event {
    @Contextual
    abstract val specId: UUID
    abstract val notificationEvent: NotificationEvent
    abstract val delivery: NotificationDelivery

    override fun identityKey(): Any = specId
}

@JobEvent(jobs = [SpecIndexJob::class, SpecContextSyncJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.created")
@Serializable
data class SpecCreated(
    @Contextual override val specId: UUID,
    @Contextual val projectId: UUID?,
    @Contextual val ownerProfileId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_CREATED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
        projectId = projectId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_CREATED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SPEC_CREATED, specId = specId, projectId = projectId),
        )
    }
}

@JobEvent(jobs = [SpecIndexJob::class, SpecContextSyncJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.updated")
@Serializable
data class SpecUpdated(
    @Contextual override val specId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_UPDATED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_UPDATED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SPEC_UPDATED, specId = specId),
        )
    }
}

@JobEvent(jobs = [SpecIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.deleted")
@Serializable
data class SpecDeleted(
    @Contextual override val specId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_DELETED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_DELETED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SPEC_DELETED, specId = specId),
        )
    }
}

@JobEvent(jobs = [SpecIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.transitioned")
@Serializable
data class SpecTransitioned(
    @Contextual override val specId: UUID,
    @Contextual val fromStatusId: UUID,
    @Contextual val toStatusId: UUID,
    @Contextual val transitionId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_TRANSITIONED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
        fromStatusId = fromStatusId,
        toStatusId = toStatusId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_TRANSITIONED,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.SPEC_TRANSITIONED,
                specId = specId,
                fromStatusId = fromStatusId,
                toStatusId = toStatusId,
            ),
        )
    }
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.commented")
@Serializable
data class SpecCommented(
    @Contextual override val specId: UUID,
    val commentId: Long,
    @Contextual val profileId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_COMMENTED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
        actorProfileId = profileId,
        commentId = commentId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_COMMENTED,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.SPEC_COMMENTED,
                specId = specId,
                actorProfileId = profileId,
                commentId = commentId,
            ),
        )
    }
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.spec.tasks_generated")
@Serializable
data class SpecTasksGenerated(
    @Contextual override val specId: UUID,
    val source: GenerationSource,
    val taskCount: Int,
    override val notificationEvent: NotificationEvent = NotificationEvent.SPEC_TASKS_GENERATED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        specId = specId,
    ),
) : SpecEvent() {
    init {
        validateSpecDelivery(
            notificationEvent,
            NotificationEvent.SPEC_TASKS_GENERATED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SPEC_TASKS_GENERATED, specId = specId),
        )
    }
}
