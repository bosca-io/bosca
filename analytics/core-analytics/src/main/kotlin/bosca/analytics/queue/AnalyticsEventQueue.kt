package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlin.time.Duration

/** Durable at-least-once handoff from collector enrichment to processor transforms and storage. */
interface AnalyticsEventQueue {
    /** Returns only after the backend has durably accepted the events. */
    suspend fun enqueue(events: Events)
    /** Claims one delivery, waiting at most [timeout]; cancellation must propagate. */
    suspend fun receive(timeout: Duration): AnalyticsEventDelivery?
    /** Claims up to [limit] deliveries; implementations may return fewer without waiting for a full batch. */
    suspend fun receiveBatch(limit: Int, timeout: Duration): List<AnalyticsEventDelivery>
    /** Durably acknowledges a processing group after its shared storage flush succeeds. */
    suspend fun acknowledgeBatch(deliveries: List<AnalyticsEventDelivery>) {
        for (delivery in deliveries) delivery.acknowledge()
    }
    /** Releases a failed processing group for redelivery after [delay]. */
    suspend fun retryBatch(deliveries: List<AnalyticsEventDelivery>, delay: Duration) {
        for (delivery in deliveries) delivery.retry(delay)
    }
    /** Checks storage independently of traffic and throws when the queue is unavailable. */
    suspend fun checkReadiness()
    /** Reports current backlog, age and storage pressure for operational inspection. */
    suspend fun status(): AnalyticsEventQueueStatus
    /** Releases consumers, file ownership and other transport resources. */
    suspend fun shutdown()
}
