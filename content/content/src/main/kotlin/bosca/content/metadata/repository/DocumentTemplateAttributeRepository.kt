package bosca.content.metadata.repository

import bosca.content.metadata.model.DocumentTemplateAttribute
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DocumentTemplateAttributeRepository {

    @Query("insert into document_template_attributes (metadata_id, version, key, name, description, supplementary_key, configuration, type, ui, list, sort, tools) values (:metadataId, :version, :key, :name, :description, :supplementaryKey, :configuration, :type, :ui, :list, :sort, :tools) returning *")
    suspend fun add(attribute: DocumentTemplateAttribute): DocumentTemplateAttribute

    @Query("select * from document_template_attributes where metadata_id = :id and version = :version order by sort")
    suspend fun getByMetadataIdAndVersion(id: UUID, version: Int): List<DocumentTemplateAttribute>

    @Query("delete from document_template_attributes where metadata_id = :id and version = :version")
    suspend fun deleteByMetadataIdAndVersion(id: UUID, version: Int)

    @Query("delete from document_template_attributes where metadata_id = :id and version = :version and key = :key")
    suspend fun deleteByMetadataIdAndVersionAndKey(id: UUID, version: Int, key: String)
}