package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.Events

/**
 * Server-side analytics emission API. Lets backend code (handlers,
 * jobs, schedulers, agents, anything else running inside a JVM) push
 * analytics events into the same pipeline that browser and mobile
 * clients use, without going through the public HTTP ingestion route.
 *
 * Two implementations ship with the framework:
 *
 * - [InProcessServerAnalyticsClient] — used by `bosca-server` and the
 *   `analytics-collector`. Calls
 *   [bosca.analytics.service.EventProcessingService.queue] directly so
 *   in-process emission has zero network overhead.
 * - [HttpServerAnalyticsClient] — used by other JVM services that are
 *   not co-located with the analytics ingestion pipeline (CLI tools,
 *   workers running in separate processes). POSTs batches to
 *   `/api/v1/events`.
 *
 * The error tracking middleware ([AnalyticsMiddleware]) routes
 * unhandled HTTP exceptions through this interface so they end up in
 * the analytics-backed error groups dashboard alongside client errors.
 *
 * Implementations are safe to call concurrently and never throw on
 * pipeline failures — they log and drop. The pipeline already has its
 * own retry / backpressure semantics; the client should not double-up
 * on those concerns.
 */
interface ServerAnalyticsClient {

    /**
     * Sends a single analytics [event]. The implementation is
     * responsible for batching, flushing, and threading. The
     * [InProcessServerAnalyticsClient] delegates to
     * [bosca.analytics.service.EventProcessingService.queue]
     * immediately; the [HttpServerAnalyticsClient] buffers and
     * flushes on a size / time trigger.
     */
    suspend fun capture(event: Event)

    /**
     * Sends one analytics [event] for a specific user or installation.
     * The configured application identity remains server-owned while
     * [userId], [installationId], and [device] populate the ordinary
     * analytics context used by warehouse identity queries.
     *
     * Use this for server-authoritative events that describe a client
     * subject. Implementations must not substitute the server host as the
     * installation when [installationId] is supplied.
     *
     * @param event event to emit
     * @param userId authenticated principal identifier, when present
     * @param installationId anonymous installation identifier, when present
     * @param device client device snapshot, when available
     */
    suspend fun captureForSubject(
        event: Event,
        userId: String?,
        installationId: String?,
        device: Device?,
    )

    /**
     * Sends a pre-built [Events] batch directly. Use this when the
     * caller already has a fully-formed batch (e.g. when forwarding
     * events between services); for the common one-off case prefer
     * [capture] or [captureException].
     */
    suspend fun capture(events: Events)

    /**
     * Builds an error event from [throwable] (type, message, stack
     * trace, fatal flag) and sends it. Optional ambient context fields
     * can be supplied to enrich the resulting event; the merged context
     * resolution rules are documented on
     * [AnalyticsErrorContextResolver].
     *
     * @param throwable the exception to capture
     * @param fatal whether the exception should be marked as fatal in
     *     the resulting [bosca.analytics.model.ErrorInfo]
     * @param appId optional override for the application id
     * @param sessionId optional override for the session id
     * @param userId optional override for the user id
     * @param context additional structured context to merge in;
     *     overrides any ambient context with overlapping keys
     */
    suspend fun captureException(
        throwable: Throwable,
        fatal: Boolean = false,
        appId: String? = null,
        sessionId: String? = null,
        userId: String? = null,
        context: Map<String, Any?> = emptyMap(),
    )

    /**
     * Flushes any pending events. The in-process client delegates to
     * the underlying pipeline; the HTTP client drains its in-memory
     * buffer. Should be called during graceful shutdown.
     */
    suspend fun flush()
}
