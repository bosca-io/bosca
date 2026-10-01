@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for upserting a single plural form of a translation.
 *
 * [pluralCategory] is typed as the enum because the GraphQL schema declares it as one —
 * a `String` here desynchronized the input binding from the SDL (the schema-first arg trap)
 * and wedged `setPluralTranslation` without a response. Applicability of a category depends
 * on the target language.
 */
@Serializable
data class LocalizationPluralTranslationInput(
    @Contextual
    val stringId: UUID,
    val languageTag: String,
    val pluralCategory: PluralCategory,
    val text: String,
    val origin: TranslationOrigin = TranslationOrigin.HUMAN,
    val originDetail: String? = null
)
