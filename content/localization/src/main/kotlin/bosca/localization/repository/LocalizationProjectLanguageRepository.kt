@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationProjectLanguage
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for `localization.project_languages`, which tracks the set of
 * target languages declared for each localization project.
 */
@Repository
interface LocalizationProjectLanguageRepository {

    @Query("select * from localization.project_languages where project_id = :projectId order by language_tag")
    suspend fun getByProjectId(projectId: UUID): List<LocalizationProjectLanguage>

    @Query("insert into localization.project_languages (project_id, language_tag) values (:projectId, :languageTag) on conflict do nothing")
    suspend fun add(projectId: UUID, languageTag: String)

    @Query("delete from localization.project_languages where project_id = :projectId and language_tag = :languageTag")
    suspend fun delete(projectId: UUID, languageTag: String)
}
