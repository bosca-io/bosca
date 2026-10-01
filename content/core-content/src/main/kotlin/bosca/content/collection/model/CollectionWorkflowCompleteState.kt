package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionWorkflowCompleteState(
    val collectionId: UUID,
    val status: String
)