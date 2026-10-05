package bosca.analytics.server

import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Builds [ErrorInfo] and [Event] instances from a JVM [Throwable].
 *
 * Kept as a top-level helper (rather than a method on
 * [ServerAnalyticsClient]) so it can be unit tested in isolation and
 * reused by tests, custom captures, and the no-op fallback client.
 */
internal object ThrowableErrorInfoMapper {

    private val log = LoggerFactory.getLogger(ThrowableErrorInfoMapper::class.java)

    /** Maximum number of characters retained from a stack trace. Matches
     *  the cap used by [bosca.analytics.ai.agents.errors.ErrorGroupAnalysisAgent]
     *  so the server-side error events and the AI prompt both stay
     *  bounded for pathological recursive crashes. */
    private const val MAX_STACK_CHARS = 32_000

    /**
     * Maps a throwable to an [ErrorInfo] suitable for the analytics
     * pipeline.
     *
     * The [context] map is serialized to JSON and attached to
     * [ErrorInfo.contextJson]. Non-serializable values (anything that
     * isn't a primitive or a JsonElement) are coerced to their
     * `toString()` form so that a call site that tries to attach a
     * rich domain object doesn't blow up the capture.
     *
     * The fingerprint is intentionally left null — it is computed
     * server-side by the
     * [bosca.analytics.transform.ErrorFingerprintTransform] so the
     * fingerprinting algorithm can evolve without coordinating client
     * upgrades.
     */
    fun toErrorInfo(
        throwable: Throwable,
        fatal: Boolean,
        context: Map<String, Any?> = emptyMap(),
    ): ErrorInfo {
        val type = throwable::class.qualifiedName ?: throwable::class.java.name
        val rawStack = throwable.stackTraceToString()
        val stack = if (rawStack.length > MAX_STACK_CHARS) {
            log.warn(
                "Truncating stack trace from {} to {} chars for {}",
                rawStack.length, MAX_STACK_CHARS, type,
            )
            rawStack.substring(0, MAX_STACK_CHARS)
        } else rawStack
        return ErrorInfo(
            message = throwable.message ?: type,
            type = type,
            stackTrace = stack,
            fatal = fatal,
            contextJson = if (context.isEmpty()) null else encodeContext(context),
        )
    }

    /**
     * Builds an error [Event] for the given throwable using the
     * current wall clock for the `created` field. Callers that have a
     * more meaningful timestamp (e.g. when the throwable was thrown
     * earlier and queued) can construct the event manually.
     */
    fun toEvent(
        throwable: Throwable,
        fatal: Boolean,
        clientId: String? = null,
        context: Map<String, Any?> = emptyMap(),
    ): Event {
        return Event(
            created = System.currentTimeMillis(),
            createdMicros = SentMicros.next(),
            type = EventType.Error,
            clientId = clientId,
            error = toErrorInfo(throwable, fatal, context),
        )
    }

    /** Serializes [context] into a compact JSON object. Values that
     *  aren't natively representable are coerced via `toString()`. */
    internal fun encodeContext(context: Map<String, Any?>): String {
        val elements = LinkedHashMap<String, JsonElement>(context.size)
        for ((key, value) in context) {
            elements[key] = when (value) {
                null -> JsonNull
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(elements))
    }
}
