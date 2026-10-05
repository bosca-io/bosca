@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Identifies which table a [TranslationHistory] row describes. Kept as a string rather
 * than a foreign key because translation history spans multiple tables
 * (`translations`, `plural_translations`, `document_translations`) with non-overlapping IDs.
 */
object TranslationHistoryTables {
    const val TRANSLATIONS = "translations"
    const val PLURAL_TRANSLATIONS = "plural_translations"
    const val DOCUMENT_TRANSLATIONS = "document_translations"
}

/**
 * An audit entry recording a single change to a translation: its state transition,
 * the actor (human, AI agent, or external sync), and both the previous and new text.
 *
 * The audit trail is append-only; history rows are never mutated.
 */
@Serializable
data class TranslationHistory(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("translation_id")
    @Contextual
    val translationId: UUID,
    /** One of [TranslationHistoryTables]. */
    @ColumnName("table_name")
    val tableName: String,
    @ColumnName("from_state")
    val fromState: TranslationState? = null,
    @ColumnName("to_state")
    val toState: TranslationState,
    @ColumnName("changed_by")
    @Contextual
    val changedBy: UUID? = null,
    val origin: TranslationOrigin,
    @ColumnName("origin_detail")
    val originDetail: String? = null,
    @ColumnName("previous_text")
    val previousText: String? = null,
    @ColumnName("new_text")
    val newText: String,
    @Contextual
    val created: OffsetDateTime? = null
)
