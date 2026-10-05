package bosca.communications.jobs

import bosca.communications.configuration.JobQueueNames
import bosca.communications.model.DeliveryEvent
import bosca.communications.service.DeliveryTrackingService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

/**
 * Persists provider-accepted delivery events independently from the external email handoff.
 *
 * Replaying the batch is safe because delivery events carry stable provider event ids.
 */
@Serializable
data class RecordDeliveryEventsJob(
    val events: List<DeliveryEvent>,
) : IJobDefinition

@JobDefinition(
    RecordDeliveryEventsJob::class,
    JobQueueNames.messagesJobQueue,
    "record-delivery-events",
)
class RecordDeliveryEventsJobExecutor(
    private val deliveryTracking: DeliveryTrackingService,
) : AbstractJobExecutor<RecordDeliveryEventsJob>(RecordDeliveryEventsJob.serializer()) {

    override suspend fun execute() {
        getJobDefinition().events.forEach { deliveryTracking.recordEvent(it) }
    }
}
