package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
class MetadataSupplementarySource(
    val metadata: Metadata,
    val supplementary: MetadataSupplementary,
)