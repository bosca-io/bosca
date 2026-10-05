package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataWorkflowState(
    val metadataId: UUID,
    val stateId: String,
    val status: String,
    val immediate: Boolean
)