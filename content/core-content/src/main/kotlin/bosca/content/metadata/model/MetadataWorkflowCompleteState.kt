package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataWorkflowCompleteState(
    val metadataId: UUID,
    val status: String
)