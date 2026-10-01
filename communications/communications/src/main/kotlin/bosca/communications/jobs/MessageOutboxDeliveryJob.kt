package bosca.communications.jobs

import bosca.communications.configuration.JobQueueNames
import bosca.communications.service.MessageOutboxService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.prepare
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Stable queue request for one communications outbox row. */
@Serializable
data class MessageOutboxDeliveryJob(
    @Contextual val outboxId: UUID,
) : IJobDefinition

@JobDefinition(
    MessageOutboxDeliveryJob::class,
    JobQueueNames.messagesJobQueue,
    "message-outbox-delivery",
)
class MessageOutboxDeliveryExecutor(
    private val outbox: MessageOutboxService,
) : AbstractJobExecutor<MessageOutboxDeliveryJob>(MessageOutboxDeliveryJob.serializer()) {

    override suspend fun execute() {
        outbox.deliver(getJobDefinition().outboxId)
    }
}

/** Builds the stable queue record owned by this outbox row. */
suspend fun MessageOutboxDeliveryJob.prepareDeliveryJob(): Job =
    prepare(
        id = outboxId,
        executor = MessageOutboxDeliveryExecutor::class,
        displayName = "Message outbox delivery",
    )

@Serializable
data object MessageOutboxMaintenanceJob : IJobDefinition

@JobDefinition(
    MessageOutboxMaintenanceJob::class,
    JobQueueNames.messagesJobQueue,
    "message-outbox-maintenance",
)
class MessageOutboxMaintenanceExecutor(
    private val outbox: MessageOutboxService,
) : AbstractJobExecutor<MessageOutboxMaintenanceJob>(MessageOutboxMaintenanceJob.serializer()) {

    override suspend fun execute() {
        outbox.recoverPending()
    }
}
