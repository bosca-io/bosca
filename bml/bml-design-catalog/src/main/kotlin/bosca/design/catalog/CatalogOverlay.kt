package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Application-owned modal drafts, outcomes and server-issued open requests. */
@Serializable
class CatalogOverlay(val id: String) {
    var name: String = "Design library"
        private set
    var error: String = ""
        private set
    var saved: String = ""
        private set
    var completed: Boolean = false
        private set
    var reopen: Boolean = false
        private set
    var rejectSaves: Boolean = false
        private set
    var triggerDisabled: Boolean = false
        private set
    var mounted: Boolean = true
        private set
    var request: Int = 0
        private set
    var draftCompleted: Boolean = false
        private set
    var submittedDraft: String = ""
        private set
    var draftRefreshes: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeSequence: Int = 0
        private set
    var noticeTone: String = "success"
        private set

    suspend fun save(value: String) {
        check(mounted && !triggerDisabled) { "The example dialog is unavailable." }
        delay(150)
        name = value
        error = if (value.trim().length < 3) "Use at least three characters." else ""
        completed = error.isEmpty() && !rejectSaves
        reopen = !completed
        if (error.isNotEmpty()) return
        if (completed) {
            saved = value
            notify("Display name saved.", "success")
        } else {
            notify("Save rejected. Your draft was kept.", "error")
        }
    }

    fun requestDraft() {
        check(mounted) { "The example dialog is unavailable." }
        request++
        draftCompleted = false
        completed = false
        reopen = false
    }

    fun finishDraft(value: String) {
        check(mounted) { "The example dialog is unavailable." }
        submittedDraft = value
        draftCompleted = true
        notify("Draft saved.", "success")
    }

    fun refresh() { notify("Example refreshed.", "info") }
    fun refreshDraft() { draftRefreshes++ }
    fun toggleRejection() { rejectSaves = !rejectSaves }
    fun toggleDisabled() { triggerDisabled = !triggerDisabled }
    fun toggleMounted() {
        mounted = !mounted
        reopen = false
        completed = false
        request = 0
        draftCompleted = false
    }

    private fun notify(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
