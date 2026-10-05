package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FigureTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf(
            "STYLE" to "fig",
            "FILE" to "image.jpg",
            "ALT" to "A picture",
            "SIZE" to "col",
            "LOC" to "top",
            "COPY" to "Public Domain",
            "REF" to "GEN 1:1"
        ))
        val fig = Figure(attrs, null, Position(0))
        assertEquals("fig", fig.style)
        assertEquals("image.jpg", fig.file)
        assertEquals("A picture", fig.alt)
        assertEquals("col", fig.size)
        assertEquals("top", fig.loc)
        assertEquals("Public Domain", fig.copy)
        assertEquals("GEN 1:1", fig.ref)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(mapOf("FILE" to "img.jpg"))
        assertFailsWith<IllegalStateException> {
            Figure(attrs, null, Position(0))
        }
    }

    @Test
    fun missingFileThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "fig"))
        assertFailsWith<IllegalStateException> {
            Figure(attrs, null, Position(0))
        }
    }

    @Test
    fun optionalFieldsAreNullWhenMissing() {
        val attrs = Attributes(mapOf("STYLE" to "fig", "FILE" to "img.jpg"))
        val fig = Figure(attrs, null, Position(0))
        assertNull(fig.alt)
        assertNull(fig.size)
        assertNull(fig.loc)
        assertNull(fig.copy)
        assertNull(fig.ref)
    }

    @Test
    fun htmlClassMatchesStyle() {
        val attrs = Attributes(mapOf("STYLE" to "fig", "FILE" to "img.jpg"))
        val fig = Figure(attrs, null, Position(0))
        assertEquals("fig", fig.htmlClass)
    }
}
