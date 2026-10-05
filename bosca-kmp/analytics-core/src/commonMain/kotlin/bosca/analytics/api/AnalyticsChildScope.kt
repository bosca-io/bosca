package bosca.analytics.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Independently cancellable child of the analytics application scope. */
internal class AnalyticsChildScope(
    parent: CoroutineScope,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])

    val scope = CoroutineScope(parent.coroutineContext + job + additionalContext)

    fun close() {
        scope.cancel()
    }
}
