package bosca.content.metadata.repository

import bosca.content.metadata.model.DocumentTemplate
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.documents.Content
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface DocumentTemplateRepository {

    @Query("insert into document_templates (metadata_id, version, default_attributes, configuration, schema, content) values (:metadataId, :version, :defaultAttributes, :configuration, :schema, :content) returning *")
    suspend fun add(template: DocumentTemplate)

    @Query("select * from document_templates inner join metadata on metadata_id = metadata.id where metadata.content_type = 'bosca/v-document-template'")
    suspend fun getAll(): List<DocumentTemplate>

    @Query("insert into document_templates (metadata_id, version, default_attributes, configuration, schema, content) values (:id, :version, :defaultAttributes, :configuration, :schema, :content) on conflict (metadata_id, version) do update set default_attributes = :defaultAttributes, configuration = :configuration, schema = :schema, content = :content")
    suspend fun add(id: UUID, version: Int, defaultAttributes: JsonElement?, configuration: JsonElement?, schema: JsonElement?, content: Content?)

    @Query("select * from document_templates where metadata_id = :id and version = :version")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): DocumentTemplate?

    @Query("select * from document_templates where metadata_id = any(:ids)")
    suspend fun getByMetadataIds(ids: List<UUID>): List<DocumentTemplate>

    @Query("delete from document_templates where metadata_id = :metadataId and version = :version")
    suspend fun deleteTemplate(metadataId: UUID, version: Int)

    @Query("update document_templates set default_attributes = :attributes where metadata_id = :metadataId and version = :version")
    suspend fun setDefaultAttributes(metadataId: UUID, version: Int, attributes: JsonElement?)

    @Query("update document_templates set configuration = :configuration where metadata_id = :metadataId and version = :version")
    suspend fun setConfiguration(metadataId: UUID, version: Int, configuration: JsonElement?)

    @Query("update document_templates set schema = :schema where metadata_id = :metadataId and version = :version")
    suspend fun setSchema(metadataId: UUID, version: Int, schema: JsonElement?)

    @Query("update document_templates set content = :content where metadata_id = :metadataId and version = :version")
    suspend fun setContent(metadataId: UUID, version: Int, content: JsonElement?)
}