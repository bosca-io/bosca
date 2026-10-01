package bosca.workops.model.bql

import kotlinx.serialization.Serializable

/**
 * One parser / validator failure. The byte-offset window points at
 * the smallest source span the error is attributable to —
 * `[start, end)` — so an LLM rewriting BQL on a retry can find the
 * exact position to fix without rerunning the parser. The optional
 * [hint] is a one-line "did you mean" suggestion when the error
 * has an unambiguous fix; otherwise null.
 */
@Serializable
data class BqlError(
    val message: String,
    val start: Int,
    val end: Int,
    val hint: String? = null,
)

/**
 * Result of [BqlParser.parse]. The parser tries to recover after the
 * first error so multiple issues can surface on a single round-trip;
 * a successful parse returns [errors] empty.
 */
@Serializable
data class BqlParseResult(
    val query: BqlQuery? = null,
    val errors: List<BqlError> = emptyList(),
) {
    val isSuccess: Boolean get() = query != null && errors.isEmpty()
}

/** Thrown when callers expect an unconditional parse and the source is invalid. */
class BqlParseException(val errors: List<BqlError>) :
    RuntimeException("BQL parse failed: ${errors.joinToString("; ") { "${it.message} at ${it.start}..${it.end}" }}")
