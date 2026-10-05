@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A single plural form of a translation. A plural [LocalizationString] has one
 * row per CLDR [PluralCategory] per target language; the set of rows that apply
 * for a given language is determined by that language's CLDR plural rules.
 *
 * Storing each category as its own row (rather than a single ICU-encoded string)
 * makes per-form editing cheap in the admin UI and maps cleanly to Crowdin's
 * per-category API. ICU encoding happens at export time.
 */
@Serializable
data class LocalizationPluralTranslation(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("string_id")
    @Contextual
    val stringId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
    @ColumnName("plural_category")
    val pluralCategory: String,
    val text: String,
    val state: TranslationState = TranslationState.DRAFT,
    val origin: TranslationOrigin = TranslationOrigin.HUMAN,
    @ColumnName("origin_detail")
    val originDetail: String? = null,
    @ColumnName("reviewed_by")
    @Contextual
    val reviewedBy: UUID? = null,
    @ColumnName("reviewed_at")
    @Contextual
    val reviewedAt: OffsetDateTime? = null,
    @ColumnName("created_by")
    @Contextual
    val createdBy: UUID? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
) {
    /** The [PluralCategory] this row applies to. Stored as a varchar in the database. */
    val category: PluralCategory
        get() = PluralCategory.fromCldrValue(pluralCategory)
}
