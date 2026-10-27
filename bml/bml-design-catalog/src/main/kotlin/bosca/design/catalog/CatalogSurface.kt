package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local example data and outcomes composed with structural library surfaces. */
@Serializable
class CatalogSurface(val id: String) {
    var loaded: Boolean = false
        private set
    var rejectLoad: Boolean = false
        private set
    var longLabels: Boolean = false
        private set
    var compact: Boolean = false
        private set
    var revision: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeTone: String = "info"
        private set
    var noticeSequence: Int = 0
        private set

    fun rows(): List<CatalogChoice> = if (!loaded) emptyList() else CatalogChoiceSearch.options.filterNot { it.disabled }.mapIndexed { index, choice ->
        if (longLabels && index == 0) choice.copy(label = "A label with <section> & \"quoted\" text " + "component".repeat(18)) else choice
    }

    fun title(): String = if (longLabels) "A deliberately long surface heading with " + "component".repeat(12) else "Local examples"

    suspend fun load() {
        delay(150)
        if (rejectLoad) {
            publish("Loading was rejected. The current examples were kept.", "error")
        } else {
            loaded = true
            publish("Examples loaded.", "success")
        }
    }

    fun clear() { loaded = false; publish("Examples cleared.", "info") }
    fun refresh() { publish("Surface example refreshed.", "info") }
    fun toggleRejection() { rejectLoad = !rejectLoad }
    fun toggleCompact() { compact = !compact }
    fun toggleLongLabels() { longLabels = !longLabels; revision++ }

    private fun publish(message: String, tone: String) {
        notice = message
        noticeTone = tone
        noticeSequence++
    }
}
