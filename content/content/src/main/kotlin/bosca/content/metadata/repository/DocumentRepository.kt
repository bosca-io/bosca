package bosca.content.metadata.repository

import bosca.content.metadata.model.Document
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.documents.Content
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface DocumentRepository {

    @Query("insert into documents (metadata_id, version, title, content) values (:id, :version, :title, :content) on conflict (metadata_id, version) do update set title = :title, content = :content")
    suspend fun add(id: UUID, version: Int, title: String, content: Content?)

    @Query("update documents set template_metadata_id = :templateMetadataId, template_metadata_version = :templateMetadataVersion where metadata_id = :id and version = :version")
    suspend fun setTemplate(id: UUID, version: Int, templateMetadataId: UUID?, templateMetadataVersion: Int?)

    @Query("insert into documents (metadata_id, version, template_metadata_id, template_metadata_version, title, content) values (:metadataId, :version, :templateMetadataId, :templateMetadataVersion, :title, :content) on conflict (metadata_id, version) do update set title = :title, content = :content, template_metadata_id = :templateMetadataId, template_metadata_version = :templateMetadataVersion")
    suspend fun add(document: Document)

    @Query("select * from documents where metadata_id = :metadataId and version = :version")
    suspend fun getByMetadataIdAndVersion(metadataId: UUID, version: Int): Document?

    /** Reads the document fields without deserializing content after a typed read fails. */
    @Query("select metadata_id, version, template_metadata_id, template_metadata_version, title, null::jsonb as content from documents where metadata_id = :metadataId and version = :version")
    suspend fun getByMetadataIdAndVersionWithoutContent(metadataId: UUID, version: Int): Document?

    /** Reads malformed document content as JSON for service-level recovery. */
    @Query("select content from documents where metadata_id = :metadataId and version = :version")
    suspend fun getRawContent(metadataId: UUID, version: Int): JsonElement?

    // TODO: version id
    @Query("select * from documents where metadata_id = any(:ids)")
    suspend fun getByMetadataIdAndVersions(ids: List<UUID>): List<Document>
}
