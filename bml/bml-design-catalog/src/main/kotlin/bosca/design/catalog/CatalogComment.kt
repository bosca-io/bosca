package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Caller-owned comment availability, draft replacement and action notifications. */
@Serializable
class CatalogComment(val id: String, val advanced: Boolean = false) {
    val draft: CatalogContent = CatalogContent(id)
    var readOnly: Boolean = false
        private set
    var disabled: Boolean = false
        private set
    var locked: Boolean = false
        private set
    var visible: Boolean = true
        private set
    var rejectPosts: Boolean = false
        private set
    var revision: Int = 0
        private set
    var replacements: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeSequence: Int = 0
        private set
    var noticeTone: String = "success"
        private set

    suspend fun post(body: String) {
        check(visible && !readOnly && !disabled && !locked) { "The example editor is unavailable for posting." }
        draft.comment(body, accepted = !rejectPosts)
        if (draft.error.isNotEmpty()) return
        if (rejectPosts) {
            notify("Post rejected. Your draft was kept.", "error")
        } else {
            if (advanced) clear()
            notify("Comment posted.", "success")
        }
    }

    fun refresh() { notify("Example refreshed.", "info") }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleLocked() { locked = !locked }
    fun toggleVisible() { visible = !visible }
    fun toggleRejection() { rejectPosts = !rejectPosts }

    fun replace() {
        draft.value = "Replacement draft ${++replacements} from this example."
        draft.error = ""
    }

    fun clear() {
        draft.value = ""
        draft.error = ""
        revision++
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
