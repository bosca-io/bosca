package bosca.analytics.persistence

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsEvent

/** Pending events that share one immutable analytics context. */
data class ContextEvents(
    val contextId: String,
    val context: AnalyticsContext,
    val events: List<AnalyticsEvent>,
)
