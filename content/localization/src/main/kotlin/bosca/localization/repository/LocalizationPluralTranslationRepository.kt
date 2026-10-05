@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for `localization.plural_translations`. The unique key is
 * `(string_id, language_tag, plural_category)` so the upsert is idempotent
 * per plural form.
 */
@Repository
interface LocalizationPluralTranslationRepository {

    @Query("select * from localization.plural_translations where id = :id")
    suspend fun getById(id: UUID): LocalizationPluralTranslation?

    @Query("select * from localization.plural_translations where string_id = :stringId and language_tag = :languageTag order by plural_category")
    suspend fun getByStringAndLanguage(stringId: UUID, languageTag: String): List<LocalizationPluralTranslation>

    @Query("select p.* from localization.plural_translations p join localization.strings s on s.id = p.string_id where s.project_id = :projectId and p.language_tag = :languageTag")
    suspend fun getByProjectAndLanguage(projectId: UUID, languageTag: String): List<LocalizationPluralTranslation>

    @Query("insert into localization.plural_translations (string_id, language_tag, plural_category, text, state, origin, origin_detail, created_by) values (:stringId, :languageTag, :pluralCategory, :text, (:state)::localization.translation_state, (:origin)::localization.translation_origin, :originDetail, :createdBy) on conflict (string_id, language_tag, plural_category) do update set text = excluded.text, state = excluded.state, origin = excluded.origin, origin_detail = excluded.origin_detail, modified = now() returning *")
    suspend fun upsert(
        stringId: UUID,
        languageTag: String,
        pluralCategory: String,
        text: String,
        state: TranslationState,
        origin: TranslationOrigin,
        originDetail: String?,
        createdBy: UUID?
    ): LocalizationPluralTranslation

    @Query("update localization.plural_translations set state = (:state)::localization.translation_state, reviewed_by = :reviewedBy, reviewed_at = now(), modified = now() where id = :id returning *")
    suspend fun transitionStateWithReviewer(id: UUID, state: TranslationState, reviewedBy: UUID): LocalizationPluralTranslation?

    @Query("update localization.plural_translations set state = (:state)::localization.translation_state, modified = now() where id = :id returning *")
    suspend fun transitionStateWithoutReviewer(id: UUID, state: TranslationState): LocalizationPluralTranslation?

    @Query("delete from localization.plural_translations where string_id = :stringId and language_tag = :languageTag")
    suspend fun deleteByStringAndLanguage(stringId: UUID, languageTag: String)
}
