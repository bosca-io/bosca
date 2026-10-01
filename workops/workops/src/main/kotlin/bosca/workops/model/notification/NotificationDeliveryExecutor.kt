package bosca.workops.model.notification

import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.workops.service.NotificationDeliveryService

@JobDefinition(NotificationDeliveryJob::class, "workops", "notification-delivery")
class NotificationDeliveryExecutor(
    private val deliveryService: NotificationDeliveryService,
) : AbstractJobExecutor<NotificationDeliveryJob>(NotificationDeliveryJob.serializer()) {

    override suspend fun execute() {
        deliveryService.deliver(getJobDefinition().delivery)
    }
}
