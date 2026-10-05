package bosca.content.metadata.repository

import bosca.content.metadata.model.DocumentTemplateContainer
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DocumentTemplateContainerRepository {

    @Query("insert into document_template_containers (metadata_id, version, id, name, description, supplementary_key, sort, type, tools, renderers, filters) values (:metadataId, :version, :id, :name, :description, :supplementaryKey, :sort, :type, :tools, :renderers, :filters)")
    suspend fun add(container: DocumentTemplateContainer)

    @Query("select * from document_template_containers where metadata_id = :metadataId and version = :version order by sort")
    suspend fun getByMetadataIdAndVersion(metadataId: UUID, version: Int): List<DocumentTemplateContainer>

    @Query("delete from document_template_containers where metadata_id = :metadataId and version = :version")
    suspend fun deleteByMetadataIdAndVersion(metadataId: UUID, version: Int)

    @Query("delete from document_template_containers where metadata_id = :metadataId and version = :version and id = :id")
    suspend fun deleteByMetadataIdAndVersionAndId(metadataId: UUID, version: Int, id: String)
}
