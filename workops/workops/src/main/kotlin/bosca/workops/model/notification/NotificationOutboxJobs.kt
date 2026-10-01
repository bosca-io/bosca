package bosca.workops.model.notification

import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.prepare
import bosca.workops.service.NotificationChannelDeliveryService
import bosca.workops.service.NotificationOutboxService
import bosca.workops.service.TaskService
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class NotificationOutboxDeliveryJob(
    @Contextual val outboxId: UUID,
) : IJobDefinition

@JobDefinition(NotificationOutboxDeliveryJob::class, "workops", "notification-outbox-delivery")
class NotificationOutboxDeliveryExecutor(
    private val outboxService: NotificationOutboxService,
    private val deliveryService: NotificationChannelDeliveryService,
) : AbstractJobExecutor<NotificationOutboxDeliveryJob>(NotificationOutboxDeliveryJob.serializer()) {

    override suspend fun execute() {
        val entry = outboxService.getPending(getJobDefinition().outboxId) ?: return
        try {
            deliveryService.deliver(entry)
            outboxService.markSent(entry.id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { outboxService.markFailure(entry.id, e.message ?: e::class.simpleName.orEmpty()) }
                .onFailure(e::addSuppressed)
            throw e
        }
    }
}

/** Creates the stable queue record owned by an outbox row. */
suspend fun NotificationOutboxEntry.prepareDeliveryJob(): Job =
    NotificationOutboxDeliveryJob(id).prepare(
        id = id,
        executor = NotificationOutboxDeliveryExecutor::class,
        displayName = "WorkOps notification outbox delivery",
    )

@Serializable
data object NotificationMaintenanceJob : IJobDefinition

@JobDefinition(NotificationMaintenanceJob::class, "workops", "notification-maintenance")
class NotificationMaintenanceExecutor(
    private val outboxService: NotificationOutboxService,
    private val taskService: TaskService,
) : AbstractJobExecutor<NotificationMaintenanceJob>(NotificationMaintenanceJob.serializer()) {

    override suspend fun execute() {
        val failures = mutableListOf<Exception>()
        runMaintenance("recover pending notifications") { outboxService.recoverPending() }?.let(failures::add)
        runMaintenance("deliver due notification digests") { outboxService.deliverDueDigests() }?.let(failures::add)
        runMaintenance("dispatch due task notifications") { taskService.dispatchDueNotifications() }?.let(failures::add)
        failures.firstOrNull()?.let { throw it }
    }

    private suspend fun runMaintenance(name: String, block: suspend () -> Unit): Exception? =
        try {
            block()
            null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to {} during notification maintenance", name, e)
            e
        }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationMaintenanceExecutor::class.java)
    }
}
