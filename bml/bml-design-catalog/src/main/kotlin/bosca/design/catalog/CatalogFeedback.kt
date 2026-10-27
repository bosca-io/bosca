package bosca.design.catalog

import kotlinx.serialization.Serializable

/** Caller-owned clipboard values and notification sequences for the feedback examples. */
@Serializable
class CatalogFeedback(val id: String) {
    var alternate: Boolean = false
        private set
    var visible: Boolean = true
        private set
    var longMessage: Boolean = false
        private set
    var revision: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeTone: String = "info"
        private set
    var noticeSequence: Int = 0
        private set

    fun value(): String = if (alternate) "Updated \"value\" & text / ?=1 " + "component".repeat(18) else "io.bosca:bml-design"
    fun description(): String = if (alternate) "Updated description: <button> remains text & keeps its meaning." else "Use the shared library through your application's unaliased import."

    fun copied(copied: Boolean) {
        publish(if (copied) "Value copied." else "Clipboard access is unavailable. Select and copy the value.", if (copied) "success" else "error")
    }

    fun notify(tone: String) {
        require(tone in listOf("info", "success", "warning", "error")) { "Choose an available notification tone." }
        val message = when (tone) {
            "success" -> "Example saved."
            "warning" -> "Review your changes before continuing."
            "error" -> "The example action was rejected. Your draft was kept."
            else -> "This notification belongs to the application."
        }
        publish(if (longMessage) (1..18).joinToString("\n") { "$it. $message Long descriptions wrap inside the notification and remain available by keyboard." } else message, tone)
    }

    fun refresh() { publish("Feedback example refreshed.", "info") }
    fun replace() { alternate = !alternate; revision++ }
    fun toggleVisible() { visible = !visible }
    fun toggleLongMessage() { longMessage = !longMessage }

    private fun publish(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
