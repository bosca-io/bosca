package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class MetadataContent(
    val metadata: Metadata,
    var allowed: Boolean = false,
)