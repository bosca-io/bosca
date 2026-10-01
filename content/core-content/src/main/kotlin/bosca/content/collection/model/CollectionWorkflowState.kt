package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionWorkflowState(
    val collectionId: UUID,
    val stateId: String,
    val status: String,
    val immediate: Boolean
)