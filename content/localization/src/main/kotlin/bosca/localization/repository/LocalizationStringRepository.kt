@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationString
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for [LocalizationString]. The `(project_id, key)` unique index is the
 * canonical lookup path used by CLI uploads and API integrations; [getByKey] returns
 * the matching row or `null` when the key has not yet been registered.
 */
@Repository
interface LocalizationStringRepository {

    @Query("select * from localization.strings where project_id = :projectId order by key offset :offset limit :limit")
    suspend fun getByProjectId(projectId: UUID, offset: Int, limit: Int): List<LocalizationString>

    @Query("select count(*) from localization.strings where project_id = :projectId")
    suspend fun countByProjectId(projectId: UUID): Int

    @Query("select * from localization.strings where id = :id")
    suspend fun getById(id: UUID): LocalizationString?

    @Query("select * from localization.strings where project_id = :projectId and key = :key")
    suspend fun getByKey(projectId: UUID, key: String): LocalizationString?

    @Query("insert into localization.strings (project_id, key, context, metadata_id, placeholders, max_length, tags, plural) values (:projectId, :key, :context, :metadataId, :placeholders, :maxLength, :tags, :plural) returning *")
    suspend fun add(string: LocalizationString): LocalizationString

    @Query("update localization.strings set key = :key, context = :context, metadata_id = :metadataId, placeholders = :placeholders, max_length = :maxLength, tags = :tags, plural = :plural, modified = now() where id = :id returning *")
    suspend fun update(string: LocalizationString): LocalizationString?

    @Query("delete from localization.strings where id = :id")
    suspend fun deleteById(id: UUID)
}
