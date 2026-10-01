@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationProjectDocument
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for project-to-metadata document links stored in
 * `localization.project_documents`. The source content always lives in the
 * metadata system; this table captures only the binding.
 */
@Repository
interface LocalizationProjectDocumentRepository {

    @Query("select * from localization.project_documents where project_id = :projectId order by created")
    suspend fun getByProjectId(projectId: UUID): List<LocalizationProjectDocument>

    @Query("select * from localization.project_documents where id = :id")
    suspend fun getById(id: UUID): LocalizationProjectDocument?

    @Query("insert into localization.project_documents (project_id, metadata_id, attributes) values (:projectId, :metadataId, :attributes) on conflict (project_id, metadata_id) do update set attributes = excluded.attributes, modified = now() returning *")
    suspend fun upsert(projectId: UUID, metadataId: UUID, attributes: JsonElement?): LocalizationProjectDocument

    @Query("delete from localization.project_documents where project_id = :projectId and metadata_id = :metadataId")
    suspend fun deleteByProjectAndMetadata(projectId: UUID, metadataId: UUID)
}
