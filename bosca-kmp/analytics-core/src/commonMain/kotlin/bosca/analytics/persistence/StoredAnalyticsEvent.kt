package bosca.analytics.persistence

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsEvent

/** One event and the immutable context snapshot it belongs to. */
data class StoredAnalyticsEvent(
    val contextId: String,
    val context: AnalyticsContext,
    val event: AnalyticsEvent,
)
