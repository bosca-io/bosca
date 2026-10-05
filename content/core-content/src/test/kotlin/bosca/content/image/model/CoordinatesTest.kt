package bosca.content.image.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatesTest {

    // --- isEmpty ---

    @Test
    fun `default coordinates are empty`() {
        val coords = Coordinates()
        assertTrue(coords.isEmpty)
    }

    @Test
    fun `coordinates with non-zero width and height are not empty`() {
        val coords = Coordinates(top = 10f, left = 20f, width = 100f, height = 50f)
        assertFalse(coords.isEmpty)
    }

    @Test
    fun `coordinates with zero width are empty even with position`() {
        val coords = Coordinates(top = 0f, left = 0f, width = 0f, height = 100f)
        assertTrue(coords.isEmpty)
    }

    @Test
    fun `coordinates with non-zero position but zero dimensions are not empty`() {
        // isZero is true (width rounds to 0), but top/left != 0, so isEmpty is false
        val coords = Coordinates(top = 10f, left = 10f, width = 0f, height = 0f)
        assertFalse(coords.isEmpty)
    }

    // --- isZero ---

    @Test
    fun `default coordinates are zero`() {
        val coords = Coordinates()
        assertTrue(coords.isZero)
    }

    @Test
    fun `coordinates with positive dimensions are not zero`() {
        val coords = Coordinates(width = 100f, height = 50f)
        assertFalse(coords.isZero)
    }

    @Test
    fun `coordinates with zero width only are zero`() {
        val coords = Coordinates(width = 0f, height = 50f)
        assertTrue(coords.isZero)
    }

    @Test
    fun `coordinates with zero height only are zero`() {
        val coords = Coordinates(width = 50f, height = 0f)
        assertTrue(coords.isZero)
    }

    @Test
    fun `coordinates with sub-half values round to zero`() {
        val coords = Coordinates(width = 0.4f, height = 0.4f)
        assertTrue(coords.isZero)
    }

    @Test
    fun `coordinates with values rounding to 1 are not zero`() {
        val coords = Coordinates(width = 0.6f, height = 0.6f)
        assertFalse(coords.isZero)
    }
}
