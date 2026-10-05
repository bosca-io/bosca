package bosca.bible.usx

import bosca.bible.BookTitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookTitleTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "mt1"))
        val bt = BookTitle(position = Position(0), attributes = attrs)
        assertEquals(BookTitleStyle.mt1, bt.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            BookTitle(position = Position(0), attributes = attrs)
        }
    }

    @Test
    fun htmlClassMatchesStyleToString() {
        val attrs = Attributes(mapOf("STYLE" to "mt"))
        val bt = BookTitle(position = Position(0), attributes = attrs)
        assertEquals(BookTitleStyle.mt.toString(), bt.htmlClass)
    }

    @Test
    fun variousTitleStyles() {
        for (styleName in listOf("mt", "mt1", "mt2", "mt3", "imt")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val bt = BookTitle(position = Position(0), attributes = attrs)
            assertEquals(BookTitleStyle.valueOf(styleName), bt.style)
        }
    }
}
