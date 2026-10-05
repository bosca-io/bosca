package bosca.analytics.api

import bosca.analytics.instrumentation.AnalyticsInstrumentationContext

/** Application analytics service resolved through Bosca DI. */
interface AnalyticsService : AnalyticsLifecycle {
    /** Records a structured analytics event in the current coroutine and page context. */
    suspend fun logEvent(event: AnalyticsEventInput)

    /** Records a visibility event; use an element type of `page` only for a genuine page impression. */
    suspend fun logImpression(element: AnalyticsElement)

    /** Records an interaction event. */
    suspend fun logInteraction(element: AnalyticsElement)

    /** Records a completion event. */
    suspend fun logCompletion(element: AnalyticsElement)

    /** Records an error event. */
    suspend fun logError(error: ErrorInfo, element: AnalyticsElement = AnalyticsElement("", "error"))

    /** Flushes all durable pending events. */
    suspend fun flush()

    /** Records an automatic event without blocking the caller. */
    fun recordAutomaticEvent(event: AnalyticsEventInput)

    /** Activates [page] and records its impression unless that page is already active. */
    fun recordAutomaticPage(owner: Any, page: Page, element: AnalyticsElement)

    /** Records an uncaught exception without blocking the failing callback. */
    fun recordUnhandledException(
        error: Throwable,
        fatal: Boolean,
        kind: String,
        context: AnalyticsInstrumentationContext = AnalyticsInstrumentationContext.Empty,
    )

    /** Persists an uncaught exception before returning. */
    suspend fun logUnhandledException(error: Throwable, fatal: Boolean, kind: String)

    /** Adds [page] to the active composition page stack for [owner]. */
    fun enterPage(owner: Any, page: Page)

    /** Removes the page owned by [owner] from the active composition page stack. */
    fun leavePage(owner: Any)
}
