package bosca.analytics.api

/** Creates immutable analytics event snapshots. */
fun interface AnalyticsEventFactory {
    /** Creates one immutable event from caller input and current platform context. */
    suspend fun createEvent(event: AnalyticsEventInput): AnalyticsEvent
}
