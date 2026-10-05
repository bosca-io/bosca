@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for upserting a single-form translation.
 *
 * [origin] defaults to [TranslationOrigin.HUMAN]; callers must set it explicitly
 * when producing AI translations so the service can enter the translation at
 * [TranslationState.AI_GENERATED] (forcing human review) instead of [TranslationState.DRAFT].
 */
@Serializable
data class LocalizationTranslationInput(
    @Contextual
    val stringId: UUID,
    val languageTag: String,
    val text: String,
    val origin: TranslationOrigin = TranslationOrigin.HUMAN,
    val originDetail: String? = null
)
