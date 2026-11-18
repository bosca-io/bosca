package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import bosca.design.model.ViewTab

/** Caller-owned search drafts, accepted results and local view choices for the catalog. */
@Serializable
class CatalogViews(val id: String) {
    var query: String = ""
        private set
    var mode: String = "all"
        private set
    var selected: String = "Preview"
        private set
    var resultQuery: String = ""
        private set
    var resultMode: String = "all"
        private set
    var disabled: Boolean = false
        private set
    var visible: Boolean = true
        private set
    var rejectSearch: Boolean = false
        private set
    var alternate: Boolean = false
        private set
    var revision: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeTone: String = "info"
        private set
    var noticeSequence: Int = 0
        private set

    fun results(): List<CatalogChoice> = CatalogChoiceSearch.options.filter {
        !it.disabled && (resultMode == "all" || it.label.endsWith("· " + resultMode)) &&
            (it.value.contains(resultQuery.trim(), true) || it.label.contains(resultQuery.trim(), true))
    }

    suspend fun search(query: String, mode: String) {
        check(visible && !disabled) { "The search example is unavailable." }
        require(mode in listOf("all", "control", "layout", "overlay")) { "Choose an available search category." }
        delay(150)
        this.query = query
        this.mode = mode
        if (rejectSearch) {
            notify("Search rejected. Your draft and previous results were kept.", "error")
        } else {
            resultQuery = query
            resultMode = mode
            notify("Search updated.", "success")
        }
    }

    fun chooseView(view: String) {
        require(tabs().any { it.value == view && !it.disabled }) { "Choose an available view." }
        selected = view
    }

    fun tabs(): List<ViewTab> = (if (alternate) listOf("Preview", "Notes") else listOf("Preview", "Details", "Source")).map { ViewTab(it, it) }
    fun refresh() { notify("Example refreshed.", "info") }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleVisible() { visible = !visible }
    fun toggleRejection() { rejectSearch = !rejectSearch }

    fun replace() {
        query = if (query == "layout") "control" else "layout"
        mode = if (mode == "layout") "control" else "layout"
        selected = if (alternate) "Notes" else "Details"
    }

    fun changeTabs() {
        alternate = !alternate
        selected = "Preview"
        revision++
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
