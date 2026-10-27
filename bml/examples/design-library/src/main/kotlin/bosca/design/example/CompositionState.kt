package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Caller-owned directory loading, comment drafts, permissions and submission outcomes. */
@Serializable
class CompositionState(val id: String) {
    var expanded: Boolean = false
        private set
    var value: String = "Seed"
        private set
    var error: String = ""
        private set
    var saved: String = ""
        private set
    var submissions: Int = 0
        private set
    var updates: Int = 0
        private set
    var revision: Int = 0
        private set
    var mounted: Boolean = true
        private set
    var locked: Boolean = false
        private set
    var failNext: Boolean = false
        private set

    suspend fun toggleDirectory() {
        delay(60)
        expanded = !expanded
    }

    suspend fun save(body: String) {
        delay(60)
        if (failNext) {
            value = body
            error = "Comment rejected; keep editing"
            failNext = false
        } else {
            saved = body
            submissions++
            value = ""
            error = ""
            revision++
        }
    }

    fun prepareFailure() { failNext = true }
    fun update() { updates++ }
    fun fill() { value = "Server draft ${++updates}" }
    fun toggle() { mounted = !mounted }
    fun toggleLocked() { locked = !locked }
}
