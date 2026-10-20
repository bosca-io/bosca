package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Application validation and submitted values for the catalog's two editable content examples. */
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

    suspend fun settings(draft: String) {
        delay(150)
        value = draft
        error = try {
            Json.parseToJsonElement(draft)
            ""
        } catch (_: SerializationException) {
            "Enter valid JSON before saving."
        }
        if (error.isEmpty()) {
            saved = draft
            saves++
        }
    }
}
