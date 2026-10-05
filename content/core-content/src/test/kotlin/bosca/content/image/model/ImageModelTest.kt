package bosca.content.image.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageModelTest {

    // --- ImageSize ---

    @Test
    fun `ImageSize size defaults to null`() {
        val size = ImageSize(name = "thumbnail", ratio = 0.5f)
        assertEquals("thumbnail", size.name)
        assertEquals(0.5f, size.ratio)
        assertNull(size.size)
    }

    @Test
    fun `ImageSize stores size coordinates`() {
        val coords = Coordinates(top = 0f, left = 0f, width = 100f, height = 100f)
        val size = ImageSize(name = "large", ratio = 1.0f, size = coords)
        assertEquals(coords, size.size)
    }

    // --- ImageResizerConfiguration ---

    @Test
    fun `ImageResizerConfiguration stores url and sizes`() {
        val sizes = listOf(
            ImageSize(name = "sm", ratio = 0.25f),
            ImageSize(name = "lg", ratio = 1.0f)
        )
        val config = ImageResizerConfiguration(url = "https://resizer.example.com", sizes = sizes)
        assertEquals("https://resizer.example.com", config.url)
        assertEquals(2, config.sizes.size)
        assertEquals("sm", config.sizes[0].name)
    }

    // --- ImageAttributes ---

    @Test
    fun `ImageAttributes defaults all fields to null`() {
        val attrs = ImageAttributes()
        assertNull(attrs.crop)
        assertNull(attrs.targetSize)
        assertNull(attrs.size)
    }

    @Test
    fun `ImageAttributes stores crop coordinates`() {
        val crop = Coordinates(top = 10f, left = 20f, width = 200f, height = 150f)
        val attrs = ImageAttributes(crop = crop)
        assertEquals(crop, attrs.crop)
    }

    @Test
    fun `ImageAttributes stores targetSize`() {
        val attrs = ImageAttributes(targetSize = listOf(800, 600))
        assertEquals(listOf(800, 600), attrs.targetSize)
    }

    @Test
    fun `ImageAttributes stores size name`() {
        val attrs = ImageAttributes(size = "large")
        assertEquals("large", attrs.size)
    }

    // --- Coordinates (additional tests beyond existing CoordinatesTest) ---

    @Test
    fun `Coordinates with non-zero dimensions is not zero`() {
        val coords = Coordinates(top = 0f, left = 0f, width = 100f, height = 50f)
        assertFalse(coords.isZero)
        assertFalse(coords.isEmpty)
    }

    @Test
    fun `Coordinates with zero width is zero`() {
        val coords = Coordinates(top = 10f, left = 20f, width = 0f, height = 50f)
        assertTrue(coords.isZero)
    }

    @Test
    fun `Coordinates with zero height is zero`() {
        val coords = Coordinates(top = 10f, left = 20f, width = 50f, height = 0f)
        assertTrue(coords.isZero)
    }

    @Test
    fun `Coordinates default is empty and zero`() {
        val coords = Coordinates()
        assertTrue(coords.isZero)
        assertTrue(coords.isEmpty)
    }

    @Test
    fun `Coordinates with non-zero position but zero size is not empty`() {
        val coords = Coordinates(top = 10f, left = 20f, width = 0f, height = 0f)
        assertTrue(coords.isZero)
        assertFalse(coords.isEmpty) // isEmpty requires isZero AND top/left are zero
    }
}
