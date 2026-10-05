package bosca.bible.usx

import bosca.bible.ListStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ListTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "li1"))
        val list = List(attrs, null, Position(0))
        assertEquals(ListStyle.li1, list.style)
    }

    @Test
    fun vidFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "li", "VID" to "GEN 1:1"))
        val list = List(attrs, null, Position(0))
        assertEquals("GEN 1:1", list.vid)
    }

    @Test
    fun vidIsNullWhenMissing() {
        val attrs = Attributes(mapOf("STYLE" to "li"))
        val list = List(attrs, null, Position(0))
        assertNull(list.vid)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            List(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "li2"))
        val list = List(attrs, null, Position(0))
        assertEquals("li2", list.htmlClass)
    }

    @Test
    fun variousListStyles() {
        for (styleName in listOf("li", "li1", "li2", "li3", "lh", "lf")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val list = List(attrs, null, Position(0))
            assertEquals(ListStyle.valueOf(styleName), list.style)
        }
    }
}
