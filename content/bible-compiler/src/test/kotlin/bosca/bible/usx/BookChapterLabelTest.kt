package bosca.bible.usx

import bosca.bible.BookChapterLabelStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookChapterLabelTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "cl"))
        val bcl = BookChapterLabel(attrs, position = Position(0))
        assertEquals(BookChapterLabelStyle.cl, bcl.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            BookChapterLabel(attrs, position = Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleToString() {
        val attrs = Attributes(mapOf("STYLE" to "cl"))
        val bcl = BookChapterLabel(attrs, position = Position(0))
        assertEquals(BookChapterLabelStyle.cl.toString(), bcl.htmlClass)
    }
}
