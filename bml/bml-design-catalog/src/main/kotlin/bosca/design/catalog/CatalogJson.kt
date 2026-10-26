package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Caller-owned JSON options and notifications around the catalog's validated draft. */
@Serializable
class CatalogJson(val id: String, val advanced: Boolean = false) {
    var value: JsonElement = buildJsonObject { put("enabled", true); put("count", 3) }
        private set
    var text: String? = null
        private set
    var error: String = ""
        private set
    var saved: JsonElement? = null
        private set
    var saves: Int = 0
        private set
    var mode: String = "tree"
        private set
    var readOnly: Boolean = false
        private set
    var disabled: Boolean = false
        private set
    var visible: Boolean = true
        private set
    var replacements: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeSequence: Int = 0
        private set
    var noticeTone: String = "success"
        private set

    suspend fun save(value: String) {
        check(visible && !readOnly && !disabled) { "The example editor is unavailable for saving." }
        delay(150)
        text = value
        error = try {
            this.value = Json.parseToJsonElement(value)
            ""
        } catch (_: SerializationException) {
            "Enter valid JSON before saving."
        }
        if (error.isEmpty()) {
            saved = this.value
            saves++
            notify("Settings saved.", "success")
        }
    }

    fun refresh() { notify("Example refreshed.", "info") }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleVisible() { visible = !visible }

    fun nextMode() {
        mode = when (mode) {
            "tree" -> "text"
            "text" -> "table"
            else -> "tree"
        }
    }

    fun replace() {
        value = buildJsonObject { put("component", "json-editor"); put("revision", ++replacements) }
        text = null
        error = ""
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
