package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import bosca.analytics.instrumentation.AnalyticsCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/** Returns a composition-owned scope carrying [LocalAnalyticsContext]. */
@Composable
fun rememberAnalyticsCoroutineScope(): CoroutineScope {
    val owner = rememberCoroutineScope()
    val service = LocalAnalytics.current
    val analytics = LocalAnalyticsContext.current
    return remember(owner, service, analytics) {
        val errors = CoroutineExceptionHandler { _, error ->
            service.recordUnhandledException(
                error,
                fatal = false,
                kind = "compose_coroutine",
                context = analytics,
            )
        }
        CoroutineScope(owner.coroutineContext + AnalyticsCoroutineContext(analytics) + errors)
    }
}
