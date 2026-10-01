package bosca.analytics.transform

import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import java.security.MessageDigest

/**
 * Computes a stable fingerprint for every [EventType.Error] event in the batch and
 * attaches it to the event's [ErrorInfo.fingerprint] field.
 *
 * The fingerprint is derived from the error class name plus a noise-stripped form of
 * the top stack frames so that the same logical bug produces the same fingerprint
 * across deploys, lambda re-numbering, anonymous class ordinals, line shifts, and
 * memory addresses. The fingerprint is scoped per `appId` so that two unrelated
 * apps reusing the same exception class do not collide into a single error group.
 *
 * Non-error events and error events that already carry a fingerprint pass through
 * untouched.
 */
class ErrorFingerprintTransform : EventPipelineTransform {

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        // Single pass: lazily allocate a new list only when we encounter
        // the first error event that needs a fingerprint. Non-error batches
        // (the common case) return the original events list unchanged.
        val appId = events.context?.appId.orEmpty()
        var transformed: ArrayList<Event>? = null
        for ((index, event) in events.events.withIndex()) {
            val error = event.error
            if (event.type != EventType.Error || error == null || error.fingerprint != null) {
                transformed?.add(event)
                continue
            }
            if (transformed == null) {
                transformed = ArrayList(events.events.size)
                for (i in 0 until index) transformed.add(events.events[i])
            }
            transformed.add(event.copy(error = error.copy(fingerprint = fingerprint(appId, error))))
        }
        return if (transformed == null) events else events.copy(events = transformed)
    }

    internal fun fingerprint(appId: String, error: ErrorInfo): String {
        val typeKey = (error.type ?: "UnknownError").trim().ifEmpty { "UnknownError" }
        val frameKey = normalizeStack(error.stackTrace).joinToString("|")
        val raw = "$appId\u0000$typeKey\u0000$frameKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(FINGERPRINT_HEX_CHARS) {
            for (i in 0 until FINGERPRINT_HEX_CHARS / 2) {
                val b = digest[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    }

    private fun normalizeStack(stackTrace: String?): List<String> {
        if (stackTrace.isNullOrBlank()) return emptyList()
        val frames = mutableListOf<String>()
        for (rawLine in stackTrace.lineSequence()) {
            if (frames.size >= MAX_FRAMES) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!FRAME_PREFIX.containsMatchIn(line)) continue
            frames += normalizeFrame(line)
        }
        return frames
    }

    private fun normalizeFrame(frame: String): String {
        var f = frame
        // Strip JVM lambda / hidden class suffixes like
        // `$$Lambda$123/0x00007f8...`. Replace with the literal `$$Lambda`
        // sentinel so the frame still starts with a stable token. The
        // replacement string is built via Regex.escapeReplacement because
        // a raw `$` in the replacement is interpreted by Java's
        // Matcher.appendReplacement as a group-reference escape and would
        // throw IllegalArgumentException: Illegal group reference.
        f = LAMBDA_HIDDEN.replace(f, LAMBDA_REPLACEMENT)
        // strip anonymous / synthetic ordinals trailing class names: Foo$1, Foo$$inlined$bar$1
        f = ANON_ORDINAL.replace(f, "")
        // strip JS / browser column:line trailers: file.js:10:42
        f = LINE_COL.replace(f, "")
        // strip hex memory addresses
        f = HEX_ADDR.replace(f, "")
        // collapse runs of whitespace
        f = WHITESPACE.replace(f, " ").trim()
        return f
    }

    companion object {
        private const val MAX_FRAMES = 5
        private const val FINGERPRINT_HEX_CHARS = 32
        private val HEX = "0123456789abcdef".toCharArray()

        private val FRAME_PREFIX = Regex("""^(at\s|[\w./$<>]+@|\S+\s+\([^)]*\)$|\S+\.\S+\()""")
        private val LAMBDA_HIDDEN = Regex("\\\$\\\$Lambda(?:\\\$\\d+)?(?:/0x[0-9a-fA-F]+)?")
        private val ANON_ORDINAL = Regex("""\$\d+""")
        private val LINE_COL = Regex(""":\d+(?::\d+)?""")
        private val HEX_ADDR = Regex("""0x[0-9a-fA-F]+""")
        private val WHITESPACE = Regex("""\s+""")

        /** Pre-escaped replacement for [LAMBDA_HIDDEN] — `$$Lambda` with
         * the two leading dollar signs escaped so Matcher.appendReplacement
         * treats them as literals. */
        private val LAMBDA_REPLACEMENT = Regex.escapeReplacement("\$\$Lambda")
    }
}
