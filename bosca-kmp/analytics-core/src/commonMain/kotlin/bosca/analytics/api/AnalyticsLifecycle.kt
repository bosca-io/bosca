package bosca.analytics.api

/** Controls process instrumentation and analytics-owned resources. */
interface AnalyticsLifecycle {
    /** Starts automatic instrumentation when supported. Calling this more than once has no effect. */
    fun start() = Unit

    /** Flushes delivery, removes process instrumentation, and releases analytics-owned resources. */
    suspend fun close()
}
