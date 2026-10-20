package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local-tree revisions and caller-owned directory loading for the catalog. */
@Serializable
class CatalogTree(val id: String) {
    var expanded: Boolean = false
        private set
    var rejectLoad: Boolean = false
        private set
    var visible: Boolean = true
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

    suspend fun toggleDirectory() {
        check(visible) { "The example tree is unavailable." }
        if (expanded) {
            expanded = false
            return
        }
        delay(150)
        if (rejectLoad) {
            notify("Directory loading rejected. Try again after turning rejection off.", "error")
        } else {
            expanded = true
        }
    }

    fun refresh() { notify("Example refreshed.", "info") }
    fun toggleRejection() { rejectLoad = !rejectLoad }
    fun toggleVisible() { visible = !visible }
    fun replace() {
        alternate = !alternate
        revision++
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
