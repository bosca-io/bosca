package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Application validation and submitted values for the catalog's comment example. */
@Serializable
data class CatalogContent(
    val id: String,
    var value: String = "",
    var error: String = "",
    var saved: String = "",
    var saves: Int = 0,
) {
    suspend fun comment(draft: String, accepted: Boolean = true) {
        delay(150)
        value = draft
        error = if (draft.trim().length < 8) "Use at least eight characters." else ""
        if (error.isEmpty() && accepted) {
            saved = draft
            saves++
        }
    }

}
