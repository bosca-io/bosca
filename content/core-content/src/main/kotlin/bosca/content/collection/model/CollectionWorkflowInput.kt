package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CollectionWorkflowInput(
    val state: String,
    @Contextual
    val deleteWorkflowId: UUID? = null
)