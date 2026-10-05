package bosca.analytics

import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.api.BoscaAnalytics
import bosca.analytics.api.DefaultAnalyticsEventFactory
import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.instrumentation.AnalyticsPageState
import bosca.analytics.instrumentation.AutomaticInstrumentationOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal fun testAnalytics(
    sink: AnalyticsEventSink,
    pageState: AnalyticsPageState = AnalyticsPageState(),
    runtimeScope: AnalyticsRuntimeScope = AnalyticsRuntimeScope(),
    automaticInstrumentation: AutomaticInstrumentationOptions = AutomaticInstrumentationOptions(
        captureUnhandledExceptions = false,
    ),
    onAutomaticError: (Throwable) -> Unit = {},
): BoscaAnalytics = BoscaAnalytics(
    factory = DefaultAnalyticsEventFactory(pageState),
    sink = sink,
    pageState = pageState,
    runtimeScope = runtimeScope,
    automaticInstrumentationOptions = automaticInstrumentation,
    logger = AnalyticsLogger { _, error -> error?.let(onAutomaticError) },
)

internal fun testRuntimeScope(parent: CoroutineScope): AnalyticsRuntimeScope = AnalyticsRuntimeScope(
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])),
)
