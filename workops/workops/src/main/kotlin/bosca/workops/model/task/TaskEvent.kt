package bosca.workops.model.task

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import bosca.workops.jobs.TaskIndexJob
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryJob
import bosca.workops.model.notification.NotificationEvent
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

private fun validateTaskDelivery(
    notificationEvent: NotificationEvent,
    expectedEvent: NotificationEvent,
    delivery: NotificationDelivery,
    expectedDelivery: NotificationDelivery,
) {
    require(notificationEvent == expectedEvent) { "Task event notification type must be $expectedEvent" }
    require(delivery == expectedDelivery) { "Task event delivery must match its event fields" }
}

@Serializable
sealed class TaskEvent : Event {
    @Contextual
    abstract val taskId: UUID
    @Contextual
    abstract val projectId: UUID
    abstract val notificationEvent: NotificationEvent
    abstract val delivery: NotificationDelivery

    override fun identityKey(): Any = taskId
}

@JobEvent(jobs = [TaskIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.created")
@Serializable
data class TaskCreated(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    @Contextual val reporterProfileId: UUID,
    @Contextual val assigneeProfileId: UUID? = null,
    override val notificationEvent: NotificationEvent = NotificationEvent.TASK_CREATED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
        assigneeProfileId = assigneeProfileId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.TASK_CREATED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.TASK_CREATED, taskId, projectId = projectId, assigneeProfileId = assigneeProfileId),
        )
    }
}

@JobEvent(jobs = [TaskIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.updated")
@Serializable
data class TaskUpdated(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    @Contextual val assigneeProfileId: UUID? = null,
    val assigneeChanged: Boolean = false,
    override val notificationEvent: NotificationEvent = NotificationEvent.TASK_UPDATED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
        assigneeProfileId = assigneeProfileId,
        assigneeChanged = assigneeChanged,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.TASK_UPDATED,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.TASK_UPDATED,
                taskId,
                projectId = projectId,
                assigneeProfileId = assigneeProfileId,
                assigneeChanged = assigneeChanged,
            ),
        )
    }
}

@JobEvent(jobs = [TaskIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.deleted")
@Serializable
data class TaskDeleted(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.TASK_DELETED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.TASK_DELETED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.TASK_DELETED, taskId, projectId = projectId),
        )
    }
}

@JobEvent(jobs = [TaskIndexJob::class, NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.transitioned")
@Serializable
data class TaskTransitioned(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    @Contextual val fromStatusId: UUID,
    @Contextual val toStatusId: UUID,
    @Contextual val transitionId: UUID,
    @Contextual val resolutionId: UUID? = null,
    override val notificationEvent: NotificationEvent = NotificationEvent.TASK_TRANSITIONED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
        fromStatusId = fromStatusId,
        toStatusId = toStatusId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.TASK_TRANSITIONED,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.TASK_TRANSITIONED,
                taskId,
                projectId = projectId,
                fromStatusId = fromStatusId,
                toStatusId = toStatusId,
            ),
        )
    }
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.commented")
@Serializable
data class TaskCommented(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    val commentId: Long,
    @Contextual val profileId: UUID,
    val mentionedProfileIds: Set<@Contextual UUID> = emptySet(),
    override val notificationEvent: NotificationEvent = NotificationEvent.TASK_COMMENTED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
        actorProfileId = profileId,
        mentionedProfileIds = mentionedProfileIds,
        commentId = commentId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.TASK_COMMENTED,
            delivery,
            NotificationDelivery(
                delivery.id,
                NotificationEvent.TASK_COMMENTED,
                taskId,
                projectId = projectId,
                actorProfileId = profileId,
                mentionedProfileIds = mentionedProfileIds,
                commentId = commentId,
            ),
        )
    }
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.sla_breached")
@Serializable
data class TaskSlaBreached(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    @Contextual val goalId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SLA_BREACHED,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.SLA_BREACHED,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SLA_BREACHED, taskId, projectId = projectId),
        )
    }
}

@JobEvent(jobs = [NotificationDeliveryJob::class], pubsubChannel = "bosca.workops.task.sla_at_risk")
@Serializable
data class TaskSlaAtRisk(
    @Contextual override val taskId: UUID,
    @Contextual override val projectId: UUID,
    @Contextual val goalId: UUID,
    override val notificationEvent: NotificationEvent = NotificationEvent.SLA_AT_RISK,
    override val delivery: NotificationDelivery = NotificationDelivery(
        event = notificationEvent,
        taskId = taskId,
        projectId = projectId,
    ),
) : TaskEvent() {
    init {
        validateTaskDelivery(
            notificationEvent,
            NotificationEvent.SLA_AT_RISK,
            delivery,
            NotificationDelivery(delivery.id, NotificationEvent.SLA_AT_RISK, taskId, projectId = projectId),
        )
    }
}
