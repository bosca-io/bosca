package bosca.analytics.queue

import kotlinx.serialization.Serializable

/** Operational backlog and capacity; null fields are not reported by the selected backend. */
@Serializable
data class AnalyticsEventQueueStatus(
    /** Unacknowledged events; disk counts sealed segments, including those currently being processed. */
    val pending: Long,
    val storageBytes: Long,
    val capacityBytes: Long? = null,
    val quarantined: Long? = null,
    val oldestPendingMillis: Long? = null,
)
