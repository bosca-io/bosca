package bosca.content.image.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class Coordinates(
    val top: Float = 0f,
    val left: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f
) {
    val isEmpty: Boolean
        get() = isZero && top.roundToInt() == 0 && left.roundToInt() == 0

    val isZero: Boolean
        get() = width.roundToInt() == 0 || height.roundToInt() == 0
}
