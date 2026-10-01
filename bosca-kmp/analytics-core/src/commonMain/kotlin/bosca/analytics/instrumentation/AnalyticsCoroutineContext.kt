package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Coroutine context element inherited by automatically instrumented asynchronous work. */
class AnalyticsCoroutineContext(
    val analytics: AnalyticsInstrumentationContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalyticsCoroutineContext>
}

/** Returns the analytics context inherited by the current coroutine. */
suspend fun currentAnalyticsContext(): AnalyticsInstrumentationContext =
    currentCoroutineContext()[AnalyticsCoroutineContext]?.analytics ?: AnalyticsInstrumentationContext.Empty

/** Runs [block] with [analytics] available to descendant coroutines. */
suspend fun <T> withAnalyticsContext(
    analytics: AnalyticsInstrumentationContext,
    block: suspend CoroutineScope.() -> T,
): T = withContext(AnalyticsCoroutineContext(currentAnalyticsContext().merge(analytics)), block)

/** Creates an owned scope that automatically reports uncaught coroutine failures. */
fun AnalyticsService.analyticsCoroutineScope(
    analytics: AnalyticsInstrumentationContext = AnalyticsInstrumentationContext.Empty,
    context: CoroutineContext = Dispatchers.Default,
): CoroutineScope {
    val errors = CoroutineExceptionHandler { _, error ->
        recordUnhandledException(error, fatal = false, kind = "coroutine", context = analytics)
    }
    return CoroutineScope(SupervisorJob() + context + AnalyticsCoroutineContext(analytics) + errors)
}
