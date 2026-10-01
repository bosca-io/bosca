package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * One container-log line emitted by `kubernetes-controller`.
 *
 * Shape mirrors the GraphQL `LogLine` type — the wire payload is one
 * NDJSON record per line over the streaming HTTP transport.
 *
 * `timestamp` is the raw kubelet-supplied RFC-3339 prefix; the studio
 * renders or hides it based on the user's "Show timestamps" toggle.
 * `level` is best-effort: heuristically derived by [LogLevelInference]
 * from the message; callers should never rely on it as authoritative.
 */
@Serializable
data class LogLine(
    val pod: String,
    val container: String,
    val timestamp: String,
    val level: EventLevel,
    val message: String,
)

/**
 * Best-effort log-level inference, used to colour log lines without
 * requiring every workload to emit structured logs. Order matters:
 * cheaper heuristics are checked first so a tight stream isn't
 * walking a long regex set per line.
 *
 * Recognises:
 *  * JSON-shaped `"level":"ERROR"` (case-insensitive)
 *  * Bracketed `[ERROR]` / `[WARN]` / `[INFO]` prefixes
 *  * Logrus / glog-style `level=error` key-value
 *  * `panic:` / `fatal:` / `error` / `warn` word starts
 *
 * Anything else falls back to INFO — matching the GraphQL schema's
 * documented contract.
 */
object LogLevelInference {

    fun classify(message: String): EventLevel {
        if (message.isEmpty()) return EventLevel.INFO
        val lower = message.lowercase()

        for ((pattern, level) in PRECISE_PATTERNS) {
            if (pattern in lower) return level
        }

        when {
            lower.startsWith("panic") -> return EventLevel.ERROR
            lower.startsWith("fatal") -> return EventLevel.ERROR
            lower.startsWith("error") -> return EventLevel.ERROR
            lower.startsWith("warn") -> return EventLevel.WARN
            lower.startsWith("debug") -> return EventLevel.INFO
            lower.startsWith("info") -> return EventLevel.INFO
        }

        return EventLevel.INFO
    }

    /**
     * Substring → level pairs checked in order. Ordering puts the most
     * structurally specific markers first so a stray "error" inside an
     * INFO message doesn't promote the level.
     */
    private val PRECISE_PATTERNS: List<Pair<String, EventLevel>> = listOf(
        "\"level\":\"error\"" to EventLevel.ERROR,
        "\"level\":\"fatal\"" to EventLevel.ERROR,
        "\"level\":\"panic\"" to EventLevel.ERROR,
        "\"level\":\"warn\""  to EventLevel.WARN,
        "\"level\":\"warning\"" to EventLevel.WARN,
        "level=error"         to EventLevel.ERROR,
        "level=fatal"         to EventLevel.ERROR,
        "level=warn"          to EventLevel.WARN,
        "level=warning"       to EventLevel.WARN,
        "[error]"             to EventLevel.ERROR,
        "[fatal]"             to EventLevel.ERROR,
        "[warn]"              to EventLevel.WARN,
        "[warning]"           to EventLevel.WARN,
    )
}
