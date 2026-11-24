package bosca.analytics.repository

import bosca.analytics.model.Events
import bosca.analytics.queue.AnalyticsEventQueue
import bosca.analytics.service.EventProcessingHealth
import kotlinx.coroutines.CancellationException

/** Collector-side durable forwarding; final analytical storage remains processor-owned. */
class QueuedEventRepository(private val queue: AnalyticsEventQueue, private val health: EventProcessingHealth) : EventRepository {
    override suspend fun process(events: Events) {
        try { queue.enqueue(events); health.queueRecovered() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { health.queueFailed(); throw e }
    }
    override suspend fun flush() { }
}
