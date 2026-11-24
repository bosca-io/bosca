package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlin.time.Duration

/** A claimed message or sealed disk segment retained until final storage succeeds. */
interface AnalyticsEventDelivery {
    /** Original event batches, each retaining its own context and timestamps. */
    val batches: List<Events>
    /** Acknowledges only after final storage and request cleanup have succeeded. */
    suspend fun acknowledge()
    /** Returns a failed delivery to the queue after a bounded retry delay. */
    suspend fun retry(delay: Duration)
    /** Keeps a transport lease alive; a disk delivery stays owned for the consumer process lifetime. */
    suspend fun renew()
}
