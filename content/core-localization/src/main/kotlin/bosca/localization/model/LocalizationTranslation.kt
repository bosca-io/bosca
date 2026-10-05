@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A translation of a non-plural [LocalizationString] for a specific language.
 *
 * Carries full workflow state ([state]), provenance ([origin], [originDetail]) and
 * review tracking ([reviewedBy], [reviewedAt]). Every change to [state] or [text]
 * is recorded in `localization.translation_history` so the full lifecycle is auditable.
 */
@Serializable
data class LocalizationTranslation(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("string_id")
    @Contextual
    val stringId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
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
)
