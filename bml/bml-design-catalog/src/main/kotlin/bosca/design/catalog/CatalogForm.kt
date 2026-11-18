package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Local form example; validation and actions belong to the consuming application. */
@Serializable
class CatalogForm(val id: String) {
    var displayName: String = "Design library"
        private set
    var updates: Boolean = false
        private set
    var saves: Int = 0
        private set
    var error: String = ""
        private set
    var message: String = ""
        private set

    suspend fun save(name: String) {
        delay(150)
        displayName = name
        message = ""
        error = if (name.trim().length < 3) "Use at least three characters." else ""
        if (error.isNotEmpty()) return
        saves++
        message = "Saved ${name.trim()}."
    }

    fun toggleUpdates() {
        updates = !updates
    }
}
