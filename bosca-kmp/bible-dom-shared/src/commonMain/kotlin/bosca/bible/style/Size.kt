package bosca.bible.style

import kotlinx.serialization.Serializable

@Serializable
data class Size(
    val size: Float,
    val unit: SizeUnit
)