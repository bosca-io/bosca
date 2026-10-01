package bosca.analytics.compose

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisibilityFractionTest {
    @Test
    fun `calculates full partial and absent visibility`() {
        assertEquals(1f, visibleFraction(Rect(10f, 10f, 30f, 30f), 100f, 100f))
        assertEquals(0.25f, visibleFraction(Rect(-10f, -10f, 10f, 10f), 100f, 100f))
        assertEquals(0f, visibleFraction(Rect(110f, 110f, 130f, 130f), 100f, 100f))
    }

    @Test
    fun `invalid dimensions have no visibility`() {
        assertEquals(0f, visibleFraction(Rect(0f, 0f, 0f, 10f), 100f, 100f))
        assertEquals(0f, visibleFraction(Rect(0f, 0f, 10f, 0f), 100f, 100f))
        assertEquals(0f, visibleFraction(Rect(0f, 0f, 10f, 10f), 0f, 100f))
        assertEquals(0f, visibleFraction(Rect(0f, 0f, 10f, 10f), 100f, 0f))
    }

    @Test
    fun `visibility configuration accepts boundaries and rejects invalid values`() {
        validateVisibilityConfiguration(0f, 0)
        validateVisibilityConfiguration(1f, 1)

        assertFailsWith<IllegalArgumentException> { validateVisibilityConfiguration(-0.1f, 0) }
        assertFailsWith<IllegalArgumentException> { validateVisibilityConfiguration(1.1f, 0) }
        assertFailsWith<IllegalArgumentException> { validateVisibilityConfiguration(0.5f, -1) }
    }
}
