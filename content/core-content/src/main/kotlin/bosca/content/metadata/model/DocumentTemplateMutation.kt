package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class DocumentTemplateMutation(
    val metadata: Metadata
)