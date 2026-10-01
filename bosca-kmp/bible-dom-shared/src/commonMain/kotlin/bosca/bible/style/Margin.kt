package bosca.bible.style

import kotlinx.serialization.Serializable

@Serializable
data class Margin(
    val top: Size?,
    val bottom: Size?,
    val left: Size?,
    val right: Size?,
)