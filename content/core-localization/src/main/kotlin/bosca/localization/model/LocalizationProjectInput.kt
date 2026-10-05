package bosca.localization.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request payload for creating or editing a [LocalizationProject].
 *
 * [sourceLanguage] defaults to `"en"` to match the common case; it must reference
 * a row in `public.languages(tag)` or the underlying insert will fail.
 */
@Serializable
data class LocalizationProjectInput(
    val name: String,
    val description: String? = null,
    val sourceLanguage: String = "en",
    @Contextual
    val attributes: JsonElement? = null
)
