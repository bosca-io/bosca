package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.service.EventProcessingService
import bosca.server.Headers
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * In-process [ServerAnalyticsClient] that calls
 * [EventProcessingService.queue] directly with no network hop. Used by
 * `bosca-server`, `bosca-runner`, `analytics-collector`, and
 * `analytics-processor`.
 *
 * The client never throws on pipeline failures: unexpected errors are
 * logged and the capture is dropped so analytics emission can never
 * crash an in-flight request. [CancellationException] is deliberately
 * re-thrown so cooperative cancellation (e.g. client disconnect,
 * graceful shutdown) propagates correctly.
 *
 * Recursion guarding: the `capture(Events)` path activates an
 * [AnalyticsCaptureGuard] around the call to
 * [EventProcessingService.queue]. If a downstream pipeline transform
 * throws, bubbles back out to the middleware, and the middleware
 * re-invokes the client, the inner call observes the active guard and
 * drops instead of recursing forever.
 *
 * The `captureException` overload builds a fresh error [Event] from
 * the [Throwable] via [ThrowableErrorInfoMapper] and attaches the
 * supplied context map and principal / session / app identifiers into
 * the resulting [ErrorInfo.contextJson] so downstream consumers
 * (error group dashboard, SQL queries) can filter on them.
 */
class InProcessServerAnalyticsClient(
    private val eventProcessingService: EventProcessingService,
    private val appId: String,
    private val contextSupplier: () -> EventPipelineContext = { EventPipelineContext(Headers.Empty) },
) : ServerAnalyticsClient {

    override suspend fun capture(event: Event) {
        capture(
            Events(
                context = ServerEventContext.build(appId),
                events = listOf(event),
                sent = System.currentTimeMillis(),
                sentMicros = SentMicros.next(),
            )
        )
    }

    override suspend fun captureForSubject(
        event: Event,
        userId: String?,
        installationId: String?,
        device: Device?,
    ) {
        capture(
            Events(
                context = ServerEventContext.build(
                    appId = appId,
                    userId = userId,
                    installationId = installationId,
                    device = device,
                ),
                events = listOf(event),
                sent = System.currentTimeMillis(),
                sentMicros = SentMicros.next(),
            )
        )
    }

    override suspend fun capture(events: Events) {
        // Recursion guard: if this coroutine is already inside an
        // in-flight capture (because an exception from a downstream
        // pipeline transform bubbled back into the middleware and was
        // re-captured), drop the new event instead of recursing.
        if (analyticsCaptureGuardActive()) {
            log.warn("dropping analytics event during capture (recursion guard active)")
            return
        }
        try {
            withAnalyticsCaptureGuard {
                eventProcessingService.queue(contextSupplier(), events)
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation must always propagate.
            throw e
        } catch (e: Throwable) {
            log.error("failed to enqueue analytics events", e)
        }
    }

    override suspend fun captureException(
        throwable: Throwable,
        fatal: Boolean,
        appId: String?,
        sessionId: String?,
        userId: String?,
        context: Map<String, Any?>,
    ) {
        // Merge the discrete principal / session / app identifiers into
        // the context map so they show up in ErrorInfo.contextJson
        // alongside any middleware-supplied entries.
        val ambientContext = AnalyticsErrorContextResolver.resolveAmbient(analyticsContext().toMap(), context)
        val merged: Map<String, Any?> = if (appId == null && sessionId == null && userId == null) {
            ambientContext
        } else {
            val out = LinkedHashMap<String, Any?>(ambientContext.size + 3)
            out.putAll(ambientContext)
            if (appId != null) out["app_id"] = appId
            if (sessionId != null) out["session_id"] = sessionId
            if (userId != null) out["user_id"] = userId
            out
        }
        capture(ThrowableErrorInfoMapper.toEvent(throwable, fatal, context = merged))
    }

    override suspend fun flush() {
        try {
            eventProcessingService.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("failed to flush analytics events", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InProcessServerAnalyticsClient::class.java)
    }
}
