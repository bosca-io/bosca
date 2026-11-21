package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A BQL diagnostic retains the server's message, source range and optional correction hint. */
@Serializable
data class WorkOpsBqlDiagnostic(
    val message: String,
    val start: Int,
    val end: Int,
    val hint: String = "",
) {
    val description: String get() = "$message (query range $start–$end)." + if (hint.isBlank()) "" else " $hint"
}
