package bosca.languages.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a [Language] entry in the supported languages list.
 */
@Serializable
data class LanguageInput(
    val tag: String,
    val name: String,
    val localName: String,
    val attributes: JsonElement? = null
)
