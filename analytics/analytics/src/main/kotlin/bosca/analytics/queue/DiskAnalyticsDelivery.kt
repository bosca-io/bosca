package bosca.analytics.queue

import bosca.analytics.model.Events
import kotlin.time.Duration

/** One sealed segment. Its event batches retain their original contexts and timestamps. */
internal class DiskAnalyticsDelivery(
    val owner: DiskAnalyticsEventQueue,
    val segment: Long,
    override val batches: List<Events>,
) : AnalyticsEventDelivery {
    override suspend fun acknowledge() = owner.acknowledgeBatch(listOf(this))
    override suspend fun retry(delay: Duration) = owner.retryBatch(listOf(this), delay)
    override suspend fun renew() = owner.renew(this)
}
