package bosca.core.analytics

/** Consumer contract for the current session identifier owned by Bosca Analytics. */
fun interface AnalyticsSessionIdProvider {
    /** Returns the current analytics session identifier without starting a new session. */
    fun sessionId(): String
}
