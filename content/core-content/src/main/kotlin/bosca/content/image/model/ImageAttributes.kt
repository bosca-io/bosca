package bosca.content.image.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageAttributes(
    val crop: Coordinates? = null,
    val targetSize: List<Int>? = null,
    val size: String? = null
)
