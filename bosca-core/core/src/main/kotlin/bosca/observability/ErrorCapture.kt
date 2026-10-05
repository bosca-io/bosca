package bosca.observability

import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Minimal functional interface for capturing exceptions into an
 * observability backend. Designed so that low-level framework modules
 * (security, graphql, storage, …) can notify an error tracking system
 * about *swallowed* exceptions — the ones that are intentionally caught
 * and logged inside an auth fallback path, a retry loop, or a callback
 * — without taking a hard compile dependency on any specific analytics
 * implementation.
 *
 * The actual implementation is provided by
 * `backend/utilities/analytics-server-client` which wires a
 * `ServerAnalyticsClient` up to this interface inside
 * `AnalyticsServerClientModule`. When no analytics module is installed,
 * `BoscaApplication` falls back to [Noop] and swallowed exceptions are only logged.
 *
 * This split mirrors the `Tracer` / `Meter` pattern used by the
 * OpenTelemetry integration: a thin API in `core` plus a concrete
 * implementation in the module that owns the transport.
 */
fun interface ErrorCapture {

    /**
     * Records [throwable] as an error event. Implementations must
     * never throw — analytics failures must not propagate back into
     * the calling code. Implementations may attach [context] entries
     * to the resulting event where the underlying schema allows.
     */
    suspend fun capture(throwable: Throwable, call: ServerCall?, context: Map<String, Any?>)

    /**
     * Default no-op implementation used when no analytics backend is
     * wired. Logs at warn level so the capture attempt still shows up
     * in operational logs.
     */
    object Noop : ErrorCapture {
        private val log = LoggerFactory.getLogger(ErrorCapture::class.java)

        override suspend fun capture(throwable: Throwable, call: ServerCall?, context: Map<String, Any?>) {
            log.warn("swallowed exception (no analytics backend wired): context={}", context, throwable)
        }
    }
}
