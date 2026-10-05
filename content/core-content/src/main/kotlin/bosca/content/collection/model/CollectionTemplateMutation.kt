package bosca.content.collection.model

import bosca.content.metadata.model.Metadata
import kotlinx.serialization.Serializable

@Serializable
data class CollectionTemplateMutation(
    val metadata: Metadata
)