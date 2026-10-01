package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
data class TextIndentInput(
    val size: Float,
    val unit: String
)