package bosca.analytics.persistence.room

/** One persisted context and the bounded set of events selected for it. */
internal data class AnalyticsContextEvents(
    val context: AnalyticsContextEntity,
    val events: List<AnalyticsEventEntity>,
)
