package bosca.analytics.api

/** Intercepts an event before it reaches a sink. */
fun interface AnalyticsEventInterceptor {
    /** Returns the event that should continue through the sink pipeline. */
    suspend fun intercept(event: AnalyticsEvent): AnalyticsEvent
}
