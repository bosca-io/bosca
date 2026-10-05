package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** DI-owned process exception instrumentation for one analytics service. */
internal class AutomaticInstrumentation(
    private val analytics: AnalyticsService,
    private val options: AutomaticInstrumentationOptions,
) {
    private var exceptionHandler: PlatformExceptionHandler? = null
    private val errorTimes = mutableListOf<Long>()

    fun start() {
        if (!options.captureUnhandledExceptions || exceptionHandler != null) return
        exceptionHandler = PlatformExceptionHandler(::captureFatal).also { it.install() }
    }

    fun close() {
        exceptionHandler?.uninstall()
        exceptionHandler = null
        errorTimes.clear()
    }

    private fun captureFatal(error: Throwable) {
        if (isThrottled()) return
        runCatching {
            runBlocking {
                withTimeoutOrNull(options.crashFlushTimeout) {
                    analytics.logUnhandledException(error, fatal = true, kind = "uncaught_exception")
                    analytics.flush()
                }
            }
        }
    }

    private fun isThrottled(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        errorTimes.removeAll { now - it >= ONE_MINUTE_MILLIS }
        if (errorTimes.size >= options.maxErrorsPerMinute) return true
        errorTimes += now
        return false
    }

    private companion object {
        const val ONE_MINUTE_MILLIS = 60_000L
    }
}
