package bosca.analytics.compose

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

internal fun visibleFraction(bounds: Rect, rootWidth: Float, rootHeight: Float): Float {
    val width = bounds.width
    val height = bounds.height
    if (width <= 0f || height <= 0f || rootWidth <= 0f || rootHeight <= 0f) return 0f
    val visibleWidth = (min(bounds.right, rootWidth) - max(bounds.left, 0f)).coerceAtLeast(0f)
    val visibleHeight = (min(bounds.bottom, rootHeight) - max(bounds.top, 0f)).coerceAtLeast(0f)
    return ((visibleWidth * visibleHeight) / (width * height)).coerceIn(0f, 1f)
}
