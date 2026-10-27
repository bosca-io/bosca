package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local form outcomes and controlled option defaults; unsaved tags remain native browser values. */
@Serializable
class CatalogLocalChoices(val id: String) {
    var component: String = ""
        private set
    var label: String = ""
        private set
    var tags: List<String> = listOf("button", "input", "card")
        private set
    var savedComponent: String = ""
        private set
    var savedLabel: String = ""
        private set
    var saves: Int = 0
        private set
    var revision: Int = 0
        private set
    var disabled: Boolean = false
        private set
    var loading: Boolean = false
        private set
    var required: Boolean = true
        private set
    var allowCustom: Boolean = true
        private set
    var visible: Boolean = true
        private set
    var rejectSaves: Boolean = false
        private set
    var maxTags: Int = 2
        private set
    var notice: String = ""
        private set
    var noticeTone: String = "success"
        private set
    var noticeSequence: Int = 0
        private set

    suspend fun save(value: String, customLabel: String) {
        check(visible && !disabled && !loading) { "The example choices are unavailable for saving." }
        require(value.isEmpty() && !required || CatalogChoiceSearch.options.any { it.value == value && !it.disabled }) {
            "Choose an available component."
        }
        delay(150)
        component = value
        label = customLabel
        if (rejectSaves) {
            notify("Save rejected. Your choices were kept.", "error")
        } else {
            savedComponent = value
            savedLabel = customLabel
            saves++
            notify("Component choice saved.", "success")
        }
    }

    fun refresh() { notify("Example refreshed.", "info") }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleLoading() { loading = !loading }
    fun toggleRequired() { required = !required }
    fun toggleCustom() { allowCustom = !allowCustom }
    fun toggleVisible() { visible = !visible }
    fun toggleRejection() { rejectSaves = !rejectSaves }
    fun changeTagLimit() { maxTags = if (maxTags == 2) 1 else 2 }

    fun replace() {
        component = if (component == "card") "button" else "card"
        label = "Imported sample"
        tags = listOf("checkbox", "switch")
        revision++
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
