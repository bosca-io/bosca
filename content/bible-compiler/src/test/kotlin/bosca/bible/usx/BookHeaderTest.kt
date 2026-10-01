package bosca.bible.usx

import bosca.bible.BookHeaderStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookHeaderTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "h"))
        val bh = BookHeader(attrs, position = Position(0))
        assertEquals(BookHeaderStyle.h, bh.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            BookHeader(attrs, position = Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "toc1"))
        val bh = BookHeader(attrs, position = Position(0))
        assertEquals("toc1", bh.htmlClass)
    }

    @Test
    fun variousHeaderStyles() {
        for (styleName in listOf("h", "ide", "rem", "toc1", "toc2", "toc3", "usfm")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val bh = BookHeader(attrs, position = Position(0))
            assertEquals(BookHeaderStyle.valueOf(styleName), bh.style)
        }
    }
}
