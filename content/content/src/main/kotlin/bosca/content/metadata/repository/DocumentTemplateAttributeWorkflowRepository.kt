package bosca.content.metadata.repository

import bosca.content.metadata.model.DocumentTemplateAttributeWorkflow
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DocumentTemplateAttributeWorkflowRepository {

    @Query("insert into document_template_attribute_workflows (metadata_id, version, key, workflow_id, auto_run) values (:metadataId, :version, :key, :workflowId, :autoRun)")
    suspend fun add(workflow: DocumentTemplateAttributeWorkflow)

    @Query("select * from document_template_attribute_workflows where metadata_id = :metadataId and version = :version and key = :key")
    suspend fun getByMetadataIdAndVersion(
        metadataId: UUID,
        version: Int,
        key: String
    ): List<DocumentTemplateAttributeWorkflow>
}