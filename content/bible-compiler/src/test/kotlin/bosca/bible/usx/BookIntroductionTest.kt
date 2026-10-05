package bosca.bible.usx

import bosca.bible.BookIntroductionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookIntroductionTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "ip"))
        val bi = BookIntroduction(attrs, null, Position(0))
        assertEquals(BookIntroductionStyle.ip, bi.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            BookIntroduction(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassContainsBookIntroductionAndStyle() {
        val attrs = Attributes(mapOf("STYLE" to "io1"))
        val bi = BookIntroduction(attrs, null, Position(0))
        assertEquals("book-introduction io1", bi.htmlClass)
    }

    @Test
    fun variousIntroStyles() {
        for (styleName in listOf("ip", "iot", "io1", "im")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val bi = BookIntroduction(attrs, null, Position(0))
            assertEquals(BookIntroductionStyle.valueOf(styleName), bi.style)
        }
    }
}
