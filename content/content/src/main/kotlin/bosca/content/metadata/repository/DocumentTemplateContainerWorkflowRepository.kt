package bosca.content.metadata.repository

import bosca.content.metadata.model.DocumentTemplateContainerWorkflow
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DocumentTemplateContainerWorkflowRepository {

    @Query("select * from document_template_container_workflows where metadata_id = :metadataId and version = :version and id = :id")
    suspend fun getByMetadataIdAndVersion(
        metadataId: UUID,
        version: Int,
        id: String
    ): List<DocumentTemplateContainerWorkflow>
}