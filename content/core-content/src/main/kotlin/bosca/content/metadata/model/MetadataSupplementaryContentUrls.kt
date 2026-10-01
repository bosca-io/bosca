package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
class MetadataSupplementaryContentUrls(
    val metadata: Metadata,
    val supplementary: MetadataSupplementary
)