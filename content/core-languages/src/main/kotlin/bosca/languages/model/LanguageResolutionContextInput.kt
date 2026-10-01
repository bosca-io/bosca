package bosca.languages.model

import kotlinx.serialization.Serializable

/** Input used to create or edit a [LanguageResolutionContext]. */
@Serializable
data class LanguageResolutionContextInput(
    val key: String,
    val name: String,
    val description: String = "",
    val fallbackLanguageTag: String,
)
