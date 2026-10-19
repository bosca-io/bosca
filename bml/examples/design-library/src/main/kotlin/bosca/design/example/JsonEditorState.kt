package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Application-owned JSON validation, drafts, save outcomes and controlled editor options. */
@Serializable
class JsonEditorState(val id: String) {
    var value: String = """{"greeting":"Hello <script>unsafe()</script>","count":2,"enabled":true,"empty":null}"""
        private set
    var error: String = ""
        private set
    var saved: String = ""
        private set
    var updates: Int = 0
        private set
    var saves: Int = 0
        private set
    var mounted: Boolean = true
        private set
    var readOnly: Boolean = false
        private set
    var disabled: Boolean = false
        private set
    var mode: String = "tree"
        private set

    suspend fun save(draft: String) {
        delay(60)
        value = draft
        try {
            Json.parseToJsonElement(draft)
            saved = draft
            saves++
            error = ""
        } catch (_: IllegalArgumentException) {
            error = "Invalid JSON; keep editing"
        }
    }

    fun update() { updates++ }
    fun replace() { value = """{"replacement":${++updates}}"""; error = "" }
    fun toggle() { mounted = !mounted }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleMode() { mode = if (mode == "tree") "text" else "tree" }
}
