package bosca.content.image.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageResizerConfiguration(
    val url: String,
    val sizes: List<ImageSize>
)
