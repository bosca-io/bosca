package bosca.design.example

import kotlinx.serialization.Serializable

/** Caller-owned notification sequences, action outcomes and disposal for the compiled consumer. */
@Serializable
class NoticeState(val id: String) {
    var message: String = ""
        private set
    var sequence: Int = 0
        private set
    var tone: String = "info"
        private set
    var duration: Int = 4000
        private set
    var updates: Int = 0
        private set
    var error: String = ""
        private set
    var mounted: Boolean = true
        private set
    private var issued: Int = 0

    fun success() = notify("success", 5000, "Saved $id")
    fun info() = notify("info", 5000, "Information for $id")
    fun warning() = notify("warning", 5000, "Review $id")
    fun failureNotice() = notify("error", 5000, "Notification for $id failed")
    fun timed() = notify("info", 2000, "Timed notice for $id")
    fun brief() = notify("info", 800, "Brief notice for $id")
    fun longNotice() = notify("warning", 10000, "Long notification for $id. ".repeat(120))

    fun update() {
        updates++
    }

    fun repeatSequence() {
        message = "Ignored replacement"
        updates++
    }

    fun olderSequence() {
        sequence = 0
        message = "Ignored older sequence"
        updates++
    }

    fun emptyNotice() {
        sequence = ++issued
        message = ""
    }

    fun fail() {
        error = "Save rejected"
        updates++
    }

    fun toggle() {
        mounted = !mounted
    }

    private fun notify(tone: String, duration: Int, message: String) {
        this.sequence = ++issued
        this.message = message
        this.tone = tone
        this.duration = duration
        error = ""
    }
}
