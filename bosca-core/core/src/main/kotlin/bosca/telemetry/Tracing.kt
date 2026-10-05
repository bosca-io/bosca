package bosca.telemetry

import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Bosca-owned trace correlation that is independent of any telemetry exporter.
 *
 * Trace state lives only in the coroutine context and follows structured coroutine execution across suspensions and
 * dispatcher changes.
 */
object Tracing {

    const val TRACE_ID_KEY = "traceId"

    /** Returns the current Bosca trace ID, or null when execution is outside a traced coroutine context. */
    suspend fun currentTraceId(): String? = currentCoroutineContext()[TraceContext]?.traceId

    /** Creates a new opaque 128-bit Bosca trace ID. */
    fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "")

    /** Returns a coroutine context that propagates [traceId] across suspensions and dispatcher changes. */
    fun asCoroutineContext(traceId: String): CoroutineContext = TraceContext(traceId)

    /**
     * Runs [block] in [traceId], inheriting the current trace when [traceId] is null and creating one when none exists.
     */
    suspend fun <T> withTrace(
        traceId: String? = null,
        block: suspend () -> T,
    ): T = withContext(asCoroutineContext(traceId ?: currentTraceId() ?: newTraceId())) {
        block()
    }

    private class TraceContext(val traceId: String) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TraceContext>

        override val key: CoroutineContext.Key<*> get() = Key
    }
}
