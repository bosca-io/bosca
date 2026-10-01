package bosca.analytics.delivery

/** Logging boundary for background delivery diagnostics. */
fun interface AnalyticsLogger {
    /** Reports a diagnostic message with an optional originating failure. */
    fun log(message: String, error: Throwable?)
}

internal fun defaultAnalyticsLogger() = AnalyticsLogger { message, error ->
    println(if (error == null) message else "$message: $error")
}
