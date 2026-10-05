package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsChildScope
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.api.AnalyticsService
import bosca.analytics.delivery.AnalyticsLogger
import bosca.graphql.client.GraphQLRequestInstrumentation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/** Owns asynchronous automatic-event work and process exception instrumentation. */
internal class AutomaticEventRecorder(
    private val analytics: AnalyticsService,
    runtimeScope: AnalyticsRuntimeScope,
    options: AutomaticInstrumentationOptions,
    logger: AnalyticsLogger,
    private val requestInstrumentation: GraphQLRequestInstrumentation,
) {
    private val instrumentation = AutomaticInstrumentation(analytics, options)
    private val requestObserver = GraphQLRequestAnalyticsObserver(analytics)
    private val captureGraphQLRequests = options.captureGraphQLRequests
    private val scope = AnalyticsChildScope(
        runtimeScope.scope,
        CoroutineExceptionHandler { _, throwable ->
            logger.log("[bosca-analytics] auto-instrumentation event failed", throwable)
        },
    )
    private var started = false

    fun start() {
        if (started) return
        started = true
        instrumentation.start()
        if (captureGraphQLRequests) requestInstrumentation.addObserver(requestObserver)
    }

    fun record(event: AnalyticsEventInput) {
        scope.scope.launch { analytics.logEvent(event) }
    }

    fun recordUnhandledException(
        error: Throwable,
        fatal: Boolean,
        kind: String,
        context: AnalyticsInstrumentationContext,
    ) {
        scope.scope.launch(AnalyticsCoroutineContext(context)) {
            analytics.logUnhandledException(error, fatal, kind)
        }
    }

    fun close() {
        if (started && captureGraphQLRequests) requestInstrumentation.removeObserver(requestObserver)
        started = false
        instrumentation.close()
        scope.close()
    }
}
