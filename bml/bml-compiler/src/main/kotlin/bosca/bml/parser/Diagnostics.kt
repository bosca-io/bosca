package bosca.bml.parser

enum class Severity { Error, Warning }

/** A diagnostic anchored at a `.bml` [span]. */
data class Diagnostic(val severity: Severity, val message: String, val span: Span)

/** The result of parsing: a (best-effort, error-recovered) [document] plus [diagnostics]. */
data class ParseResult(val document: Document, val diagnostics: List<Diagnostic>) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }
}
