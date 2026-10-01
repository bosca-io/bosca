@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationDocumentTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for per-language document translations stored in
 * `localization.document_translations`. The `content` column is JSONB and mirrors
 * the schema of the source metadata document.
 */
@Repository
interface LocalizationDocumentTranslationRepository {

    @Query("select * from localization.document_translations where id = :id")
    suspend fun getById(id: UUID): LocalizationDocumentTranslation?

    @Query("select * from localization.document_translations where document_id = :documentId")
    suspend fun getByDocumentId(documentId: UUID): List<LocalizationDocumentTranslation>

    @Query("select * from localization.document_translations where document_id = :documentId and language_tag = :languageTag")
    suspend fun getByDocumentAndLanguage(documentId: UUID, languageTag: String): LocalizationDocumentTranslation?

    @Query("insert into localization.document_translations (document_id, language_tag, content, state, origin, origin_detail, created_by) values (:documentId, :languageTag, :content, (:state)::localization.translation_state, (:origin)::localization.translation_origin, :originDetail, :createdBy) on conflict (document_id, language_tag) do update set content = excluded.content, state = excluded.state, origin = excluded.origin, origin_detail = excluded.origin_detail, modified = now() returning *")
    suspend fun upsert(
        documentId: UUID,
        languageTag: String,
        content: JsonElement,
        state: TranslationState,
        origin: TranslationOrigin,
        originDetail: String?,
        createdBy: UUID?
    ): LocalizationDocumentTranslation

    @Query("update localization.document_translations set state = (:state)::localization.translation_state, reviewed_by = :reviewedBy, reviewed_at = now(), modified = now() where id = :id returning *")
    suspend fun transitionStateWithReviewer(id: UUID, state: TranslationState, reviewedBy: UUID): LocalizationDocumentTranslation?

    @Query("update localization.document_translations set state = (:state)::localization.translation_state, modified = now() where id = :id returning *")
    suspend fun transitionStateWithoutReviewer(id: UUID, state: TranslationState): LocalizationDocumentTranslation?
}
