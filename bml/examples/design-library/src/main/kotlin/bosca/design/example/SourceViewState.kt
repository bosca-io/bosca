package bosca.design.example

import kotlinx.serialization.Serializable

/** Caller-owned source content revisions and requested viewer options. */
@Serializable
class SourceViewState(val id: String) {
    var updates: Int = 0
        private set
    var revision: Int = 0
        private set
    var line: Int = 3
        private set
    var language: String = "typescript"
        private set
    var wrapped: Boolean = false
        private set
    var mounted: Boolean = true
        private set
    val content: String get() = if (revision == 0) SourceSamples.long else "// Replaced source\nconst replacement = $revision;"

    fun update() { updates++ }
    fun requestLine() { line = 50 }
    fun toggleLanguage() { language = if (language == "typescript") "text" else "typescript" }
    fun toggleWrapping() { wrapped = !wrapped }
    fun replace() { revision++; line = 1 }
    fun toggle() { mounted = !mounted }
}
