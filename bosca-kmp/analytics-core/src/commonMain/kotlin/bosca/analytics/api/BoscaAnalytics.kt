package bosca.analytics.api

import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.instrumentation.AutomaticEventRecorder
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import bosca.analytics.instrumentation.AnalyticsPageState
import bosca.analytics.instrumentation.AutomaticInstrumentationOptions
import bosca.analytics.instrumentation.currentAnalyticsContext
import bosca.analytics.instrumentation.withInstrumentationContext
import bosca.analytics.platform.PlatformAnalyticsAdapter
import bosca.graphql.client.GraphQLRequestInstrumentation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default DI-created implementation of Bosca analytics. */
class BoscaAnalytics(
    private val factory: AnalyticsEventFactory,
    private val sink: AnalyticsEventSink,
    private val pageState: AnalyticsPageState,
    private val runtimeScope: AnalyticsRuntimeScope,
    automaticInstrumentationOptions: AutomaticInstrumentationOptions,
    private val logger: AnalyticsLogger,
    requestInstrumentation: GraphQLRequestInstrumentation = GraphQLRequestInstrumentation(),
) : AnalyticsService, bosca.core.platform.Analytics {
    private val platformAnalytics = PlatformAnalyticsAdapter(this)
    private val automaticEvents = AutomaticEventRecorder(
        analytics = this,
        runtimeScope = runtimeScope,
        options = automaticInstrumentationOptions,
        logger = logger,
        requestInstrumentation = requestInstrumentation,
    )
    private val closeMutex = Mutex()
    private var closed = false

    override fun start() {
        check(!closed) { "Analytics has already been closed" }
        automaticEvents.start()
    }

    override suspend fun logEvent(event: AnalyticsEventInput) {
        val created = factory.createEvent(event.withInstrumentationContext(currentAnalyticsContext()))
        sink.add(created)
    }

    override suspend fun logImpression(element: AnalyticsElement) =
        logEvent(AnalyticsEventInput(AnalyticsEventType.IMPRESSION, element))

    override suspend fun logInteraction(element: AnalyticsElement) =
        logEvent(AnalyticsEventInput(AnalyticsEventType.INTERACTION, element))

    override suspend fun logCompletion(element: AnalyticsElement) =
        logEvent(AnalyticsEventInput(AnalyticsEventType.COMPLETION, element))

    override suspend fun logError(error: ErrorInfo, element: AnalyticsElement) =
        logEvent(AnalyticsEventInput(AnalyticsEventType.ERROR, element, error = error))

    override suspend fun flush() = sink.flush()

    override fun recordAutomaticEvent(event: AnalyticsEventInput) {
        automaticEvents.record(event)
    }

    override fun recordAutomaticPage(owner: Any, page: Page, element: AnalyticsElement) {
        if (pageState.enter(owner, page)) {
            recordAutomaticEvent(
                AnalyticsEventInput(
                    type = AnalyticsEventType.IMPRESSION,
                    element = element,
                    page = page,
                ),
            )
        }
    }

    override fun recordUnhandledException(
        error: Throwable,
        fatal: Boolean,
        kind: String,
        context: AnalyticsInstrumentationContext,
    ) {
        automaticEvents.recordUnhandledException(error, fatal, kind, context)
    }

    override suspend fun logUnhandledException(error: Throwable, fatal: Boolean, kind: String) {
        logEvent(
            AnalyticsEventInput(
                type = AnalyticsEventType.ERROR,
                element = AnalyticsElement(
                    id = error::class.simpleName.orEmpty(),
                    type = kind,
                    extras = mapOf("instrumentation" to "automatic"),
                ),
                error = ErrorInfo(
                    message = error.message ?: error.toString(),
                    type = error::class.simpleName,
                    stackTrace = error.stackTraceToString(),
                    fatal = fatal,
                ),
            ),
        )
    }

    override fun enterPage(owner: Any, page: Page) {
        pageState.enter(owner, page)
    }

    override fun leavePage(owner: Any) {
        pageState.leave(owner)
    }

    override fun logEvent(name: String, params: Map<String, Any>) {
        platformAnalytics.logEvent(name, params)
    }

    override fun logError(
        message: String,
        type: String?,
        stackTrace: String?,
        fatal: Boolean,
        code: String?,
        params: Map<String, Any>,
    ) {
        platformAnalytics.logError(message, type, stackTrace, fatal, code, params)
    }

    override suspend fun close() = closeMutex.withLock {
        if (closed) return@withLock
        closed = true
        try {
            automaticEvents.close()
            sink.close()
        } finally {
            pageState.clear()
            runtimeScope.close()
        }
    }
}
