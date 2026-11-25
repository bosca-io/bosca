package bosca.content.image.model

import kotlinx.serialization.Serializable

/** An image relationship declared by a content template. */
@Serializable
data class TemplateImageAttribute(
    val key: String,
    val name: String,
    val description: String,
    val relationship: String,
    val list: Boolean,
    val targetSize: List<Int>? = null,
    val defaultAspectRatio: Double? = null,
)
