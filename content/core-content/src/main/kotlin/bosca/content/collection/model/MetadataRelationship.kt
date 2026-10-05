package bosca.content.collection.model

import bosca.content.metadata.model.Metadata
import kotlinx.serialization.Serializable

@Serializable
data class MetadataRelationship(
    val metadata: Metadata,
    val relationship: String
)