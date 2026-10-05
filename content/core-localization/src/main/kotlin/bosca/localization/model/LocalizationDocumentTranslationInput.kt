@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for upserting the translated content of a project document
 * for a specific language.
 */
@Serializable
data class LocalizationDocumentTranslationInput(
    @Contextual
    val documentId: UUID,
    val languageTag: String,
    @Contextual
    val content: JsonElement,
    val origin: TranslationOrigin = TranslationOrigin.HUMAN,
    val originDetail: String? = null
)
