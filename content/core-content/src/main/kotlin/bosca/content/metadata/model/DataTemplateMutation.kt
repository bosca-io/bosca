package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class DataTemplateMutation(
    val metadata: Metadata
)