@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.TranslationHistory
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for the append-only audit log in `localization.translation_history`.
 *
 * History rows are never mutated; each state transition or text edit produces a new row.
 * Rows reference either `translations`, `plural_translations`, or `document_translations`
 * via the composite `(translation_id, table_name)` pair since their IDs do not share a key space.
 */
@Repository
interface LocalizationHistoryRepository {

    @Query("insert into localization.translation_history (translation_id, table_name, from_state, to_state, changed_by, origin, origin_detail, previous_text, new_text) values (:translationId, :tableName, case when :fromState is null then null else (:fromState)::localization.translation_state end, (:toState)::localization.translation_state, :changedBy, (:origin)::localization.translation_origin, :originDetail, :previousText, :newText) returning *")
    suspend fun insert(
        translationId: UUID,
        tableName: String,
        fromState: TranslationState?,
        toState: TranslationState,
        changedBy: UUID?,
        origin: TranslationOrigin,
        originDetail: String?,
        previousText: String?,
        newText: String
    ): TranslationHistory

    @Query("select * from localization.translation_history where translation_id = :translationId order by created")
    suspend fun getByTranslationId(translationId: UUID): List<TranslationHistory>
}
