package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable


@Serializable

class DocumentTemplateContainerWorkflow(
    val metadataId: UUID,
    val version: Int,
    val id: String,
    val name: String,
    val workflowId: String,
    val autoRun: Boolean = false,
)