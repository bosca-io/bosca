@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationProjectFormat
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for `localization.project_formats`, which tracks the set of
 * export formats configured for each localization project.
 */
@Repository
interface LocalizationProjectFormatRepository {

    @Query("select * from localization.project_formats where project_id = :projectId order by format")
    suspend fun getByProjectId(projectId: UUID): List<LocalizationProjectFormat>

    @Query("insert into localization.project_formats (project_id, format) values (:projectId, :format) on conflict do nothing")
    suspend fun add(projectId: UUID, format: String)

    @Query("delete from localization.project_formats where project_id = :projectId and format = :format")
    suspend fun delete(projectId: UUID, format: String)
}
