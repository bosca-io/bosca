package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
class DataTemplateAttributeWorkflow(
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    val key: String,
    @ColumnName("workflow_id")
    val workflowId: String,
    @ColumnName("auto_run")
    val autoRun: Boolean = false,
)