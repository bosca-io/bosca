package bosca.content.image.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageSize(
    val name: String,
    val ratio: Float,
    val size: Coordinates? = null
)
