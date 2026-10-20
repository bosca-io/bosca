package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Caller-owned JSON options and notifications around the catalog's validated draft. */
@Serializable
class CatalogJson(val id: String, val advanced: Boolean = false) {
    val draft: CatalogContent = CatalogContent(id, "{\"enabled\": true, \"count\": 3}")
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
        draft.settings(value)
        if (draft.error.isEmpty()) notify("Settings saved.", "success")
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
        draft.value = "{\"component\":\"json-editor\",\"revision\":${++replacements}}"
        draft.error = ""
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
