@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Source-language text and translator context supplied to the configured AI translation provider. */
@Serializable
data class LocalizationAITranslationSource(
    @Contextual
    val stringId: UUID,
    val key: String,
    val context: String? = null,
    val text: String,
    val placeholders: JsonElement? = null,
    val maxLength: Int? = null,
)

/** A generic localization request; the localization service constructs this from its authoritative records. */
@Serializable
data class LocalizationAITranslationRequest(
    val sourceLanguageTag: String,
    val targetLanguageTags: List<String>,
    val sources: List<LocalizationAITranslationSource>,
)

/** One generated target-language value returned by an AI translation provider. */
@Serializable
data class LocalizationAITranslationResult(
    @Contextual
    val stringId: UUID,
    val languageTag: String,
    val text: String,
)
