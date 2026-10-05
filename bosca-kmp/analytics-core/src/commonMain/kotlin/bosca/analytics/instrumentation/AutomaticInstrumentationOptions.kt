package bosca.analytics.instrumentation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runtime options for automatic platform and coroutine failure capture. */
data class AutomaticInstrumentationOptions(
    val captureUnhandledExceptions: Boolean = true,
    val captureGraphQLRequests: Boolean = true,
    val maxErrorsPerMinute: Int = 30,
    val crashFlushTimeout: Duration = 2.seconds,
) {
    init {
        require(maxErrorsPerMinute > 0) { "Maximum errors per minute must be positive" }
        require(crashFlushTimeout.isPositive()) { "Crash flush timeout must be positive" }
    }
}
