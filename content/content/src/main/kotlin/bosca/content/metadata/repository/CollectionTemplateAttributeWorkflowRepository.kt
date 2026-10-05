package bosca.content.metadata.repository

import bosca.content.collection.model.CollectionTemplateAttributeWorkflow
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionTemplateAttributeWorkflowRepository {

    @Query("select * from collection_template_attribute_workflows where metadata_id = any(:metadataId)")
    suspend fun getByMetadataIds(metadataId: List<UUID>): List<CollectionTemplateAttributeWorkflow>

    @Query("select * from collection_template_attribute_workflows where metadata_id = :metadataId and version = :version")
    suspend fun getByMetadataIdAndVersionAndKey(
        metadataId: UUID,
        version: Int,
        key: String
    ): List<CollectionTemplateAttributeWorkflow>

    @Query("insert into collection_template_attribute_workflows (metadata_id, version, key, workflow_id, auto_run) values (:metadataId, :version, :key, :workflowId, :autoRun)")
    suspend fun add(workflow: CollectionTemplateAttributeWorkflow)
}