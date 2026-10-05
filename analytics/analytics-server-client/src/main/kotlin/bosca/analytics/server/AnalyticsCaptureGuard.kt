package bosca.analytics.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that marks the current coroutine as
 * "currently inside an analytics capture call". The
 * [InProcessServerAnalyticsClient] both sets and reads this element
 * around its call to `EventProcessingService.queue`:
 *
 * 1. On entry to `capture(Events)`, if the guard is already active
 *    the call is dropped — that means we are recursing.
 * 2. If it's not active, the client activates it via
 *    [withAnalyticsCaptureGuard] for the duration of the pipeline
 *    call, so any downstream code that re-invokes the client (for
 *    example, a pipeline transform that throws into the middleware
 *    and the middleware re-captures) observes the active guard and
 *    drops, breaking the recursion cycle.
 *
 * Callers from job executors, background workers, middleware, etc.
 * do **not** need to wrap their own code — the client handles it.
 * The helper is exposed primarily so tests can simulate an already-
 * active guard without constructing a pipeline.
 *
 * **Important**: Because this is a [CoroutineContext.Element], the
 * guard is only visible to coroutines that inherit the context. If
 * downstream code launches a new coroutine with a fresh context
 * (e.g., `launch(Dispatchers.Default) { ... }` without propagating
 * the parent context), the guard will not be present and the
 * recursion protection will not apply. Always use structured
 * concurrency or explicitly include the parent context when spawning
 * child coroutines inside analytics-captured code paths.
 */
class AnalyticsCaptureGuard : CoroutineContext.Element {

    companion object Key : CoroutineContext.Key<AnalyticsCaptureGuard>

    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Returns true if the current coroutine context already contains an
 * [AnalyticsCaptureGuard], indicating an analytics capture is in
 * progress and downstream emissions should be dropped to avoid
 * recursion.
 */
suspend fun analyticsCaptureGuardActive(): Boolean =
    currentCoroutineContext()[AnalyticsCaptureGuard.Key] != null

/**
 * Runs [block] inside a coroutine scope flagged as an in-progress
 * analytics capture. Any nested calls to
 * [InProcessServerAnalyticsClient.capture] inside [block] will become
 * no-ops, breaking the recursion cycle described on
 * [AnalyticsCaptureGuard].
 */
suspend inline fun <T> withAnalyticsCaptureGuard(crossinline block: suspend () -> T): T =
    withContext(AnalyticsCaptureGuard()) { block() }
