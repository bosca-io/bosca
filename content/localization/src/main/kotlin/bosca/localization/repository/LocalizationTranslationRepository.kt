@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for plain (non-plural) translations stored in `localization.translations`.
 *
 * The upsert semantics in [upsert] rely on the `(string_id, language_tag)` unique index
 * so callers can set a translation without first checking for existence. The `state` and
 * `origin` parameters are cast to the localization schema's Postgres enum types.
 *
 * `created_by` is intentionally immutable after the initial insert — it records the
 * original author. Subsequent edits by different users are tracked via the audit
 * history in `localization.translation_history`.
 */
@Repository
interface LocalizationTranslationRepository {

    @Query("select * from localization.translations where id = :id")
    suspend fun getById(id: UUID): LocalizationTranslation?

    @Query("select * from localization.translations where string_id = :stringId")
    suspend fun getByStringId(stringId: UUID): List<LocalizationTranslation>

    @Query("select * from localization.translations where string_id = :stringId and language_tag = :languageTag")
    suspend fun getByStringIdAndLanguage(stringId: UUID, languageTag: String): LocalizationTranslation?

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.state = (:state)::localization.translation_state")
    suspend fun getByProjectAndState(projectId: UUID, state: TranslationState): List<LocalizationTranslation>

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.origin = (:origin)::localization.translation_origin")
    suspend fun getByProjectAndOrigin(projectId: UUID, origin: TranslationOrigin): List<LocalizationTranslation>

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.language_tag = :languageTag")
    suspend fun getByProjectAndLanguage(projectId: UUID, languageTag: String): List<LocalizationTranslation>

    @Query("insert into localization.translations (string_id, language_tag, text, state, origin, origin_detail, created_by) values (:stringId, :languageTag, :text, (:state)::localization.translation_state, (:origin)::localization.translation_origin, :originDetail, :createdBy) on conflict (string_id, language_tag) do update set text = excluded.text, state = excluded.state, origin = excluded.origin, origin_detail = excluded.origin_detail, modified = now() returning *")
    suspend fun upsert(
        stringId: UUID,
        languageTag: String,
        text: String,
        state: TranslationState,
        origin: TranslationOrigin,
        originDetail: String?,
        createdBy: UUID?
    ): LocalizationTranslation

    @Query("update localization.translations set state = (:state)::localization.translation_state, reviewed_by = :reviewedBy, reviewed_at = now(), modified = now() where id = :id returning *")
    suspend fun transitionStateWithReviewer(id: UUID, state: TranslationState, reviewedBy: UUID): LocalizationTranslation?

    @Query("update localization.translations set state = (:state)::localization.translation_state, modified = now() where id = :id returning *")
    suspend fun transitionStateWithoutReviewer(id: UUID, state: TranslationState): LocalizationTranslation?

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.language_tag = :languageTag and t.state = (:state)::localization.translation_state")
    suspend fun getByProjectLanguageAndState(
        projectId: UUID,
        languageTag: String,
        state: TranslationState
    ): List<LocalizationTranslation>

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.language_tag = :languageTag and t.origin = (:origin)::localization.translation_origin")
    suspend fun getByProjectLanguageAndOrigin(
        projectId: UUID,
        languageTag: String,
        origin: TranslationOrigin
    ): List<LocalizationTranslation>

    @Query("select t.* from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.language_tag = :languageTag and t.state = (:fromState)::localization.translation_state for update")
    suspend fun lockForBulkTransition(
        projectId: UUID,
        languageTag: String,
        fromState: TranslationState
    ): List<LocalizationTranslation>

    @Query("delete from localization.translations where string_id = :stringId and language_tag = :languageTag")
    suspend fun deleteByStringAndLanguage(stringId: UUID, languageTag: String)

    @Query("select count(*) filter (where t.state = 'draft' or t.state = 'ai_generated' or t.state = 'in_review' or t.state = 'approved' or t.state = 'published') as translated_strings, count(*) filter (where t.state = 'approved' or t.state = 'published') as approved_strings, count(*) filter (where t.state = 'published') as published_strings, count(*) filter (where t.origin = 'ai') as ai_generated_strings, count(*) filter (where t.origin = 'human') as human_translated_strings from localization.translations t join localization.strings s on s.id = t.string_id where s.project_id = :projectId and t.language_tag = :languageTag")
    suspend fun getProgressCounts(projectId: UUID, languageTag: String): TranslationProgressRow?
}

/** Row returned by the progress-counts aggregate query; mapped into [bosca.localization.model.TranslationProgress]. */
data class TranslationProgressRow(
    @bosca.db.annotation.ColumnName("translated_strings")
    val translatedStrings: Int,
    @bosca.db.annotation.ColumnName("approved_strings")
    val approvedStrings: Int,
    @bosca.db.annotation.ColumnName("published_strings")
    val publishedStrings: Int,
    @bosca.db.annotation.ColumnName("ai_generated_strings")
    val aiGeneratedStrings: Int,
    @bosca.db.annotation.ColumnName("human_translated_strings")
    val humanTranslatedStrings: Int
)
