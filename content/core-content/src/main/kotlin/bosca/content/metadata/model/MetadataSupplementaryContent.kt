package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class MetadataSupplementaryContent(
    val metadata: Metadata,
    val supplementary: MetadataSupplementary
)