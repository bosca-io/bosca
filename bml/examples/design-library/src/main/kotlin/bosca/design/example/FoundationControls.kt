package bosca.design.example

import kotlinx.serialization.Serializable

/** Consumer-owned form state; design controls render values and dispatch the caller's actions. */
@Serializable
class FoundationControls(val id: String) {
    var subscribed: Boolean = false
        private set
    var enabled: Boolean = false
        private set
    var submissions: Int = 0
        private set
    var submitted: String = ""
        private set

    fun toggleSubscription() {
        subscribed = !subscribed
    }

    fun toggleEnabled() {
        enabled = !enabled
    }

    fun save(subscription: Boolean) {
        submitted = if (subscription) "yes" else ""
        submissions++
    }
}
