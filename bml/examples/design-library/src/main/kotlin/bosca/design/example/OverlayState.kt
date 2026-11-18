package bosca.design.example

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Application-owned modal outcomes and server-issued open requests for the compiled consumer. */
@Serializable
class OverlayState(val id: String) {
    var draft: String = "Ada"
        private set
    var message: String = ""
        private set
    var attempts: Int = 0
        private set
    var completed: Boolean = false
        private set
    var forceOpen: Boolean = false
        private set
    var request: Int = 0
        private set
    var bumps: Int = 0
        private set
    var confirmations: Int = 0
        private set
    var mounted: Boolean = true
        private set

    suspend fun save(name: String) {
        require(name.isNotBlank()) { "A name is required" }
        draft = name
        delay(250)
        attempts++
        completed = name != "retry"
        forceOpen = !completed
        message = if (completed) "Saved $name" else "Try a different name"
    }

    fun requestOpen() {
        request++
        completed = false
        forceOpen = false
    }

    fun openForced() {
        completed = false
        forceOpen = true
    }

    fun complete() {
        completed = true
        forceOpen = false
    }

    fun bump() {
        bumps++
    }

    fun confirm() {
        confirmations++
    }

    fun toggle() {
        mounted = !mounted
    }
}
