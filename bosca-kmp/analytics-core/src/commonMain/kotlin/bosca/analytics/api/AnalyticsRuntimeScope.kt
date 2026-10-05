package bosca.analytics.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** DI-owned application scope used for analytics delivery and automatic instrumentation. */
class AnalyticsRuntimeScope(
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Cancels all analytics-owned background work. */
    fun close() {
        scope.cancel()
    }
}
