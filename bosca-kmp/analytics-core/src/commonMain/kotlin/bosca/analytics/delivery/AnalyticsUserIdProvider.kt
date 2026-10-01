package bosca.analytics.delivery

/** Supplies the authenticated user ID captured with each analytics event. */
fun interface AnalyticsUserIdProvider {
    fun currentUserId(): String?

    companion object {
        /** Provider for applications that do not have authenticated users. */
        val Anonymous = AnalyticsUserIdProvider { null }
    }
}
