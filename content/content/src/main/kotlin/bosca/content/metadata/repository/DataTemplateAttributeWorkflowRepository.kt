package bosca.content.metadata.repository

import bosca.content.metadata.model.DataTemplateAttributeWorkflow
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface DataTemplateAttributeWorkflowRepository {

    @Query("insert into data_template_attribute_workflows (metadata_id, version, key, workflow_id, auto_run) values (:metadataId, :version, :key, :workflowId, :autoRun)")
    suspend fun add(workflow: DataTemplateAttributeWorkflow)

    @Query("select * from data_template_attribute_workflows where metadata_id = :metadataId and version = :version and key = :key")
    suspend fun getByMetadataIdAndVersion(
        metadataId: UUID,
        version: Int,
        key: String
    ): List<DataTemplateAttributeWorkflow>
}