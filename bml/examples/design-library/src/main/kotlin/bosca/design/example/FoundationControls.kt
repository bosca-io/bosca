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
    var slugTitle: String = ""
        private set
    var slug: String = ""
        private set
    var slugManual: Boolean = false
        private set
    var slugSaves: Int = 0
        private set
    var slugResets: Int = 0
        private set
    var color: String = "#91a6f680"
        private set
    var colorSaves: Int = 0
        private set
    var colorResets: Int = 0
        private set

    fun saveColor(value: String) { color = value; colorSaves++ }
    fun resetColor() { color = ""; colorResets++ }

    fun saveSlug(title: String, value: String, manual: Boolean) {
        slugTitle = title; slug = value; slugManual = manual; slugSaves++
    }

    fun resetSlug() { slugTitle = ""; slug = ""; slugManual = false; slugResets++ }

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
