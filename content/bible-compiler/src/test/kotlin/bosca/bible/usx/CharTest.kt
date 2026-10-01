package bosca.bible.usx

import bosca.bible.CharStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CharTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "wj"))
        val char = Char(attrs, null, Position(0))
        assertEquals(CharStyle.wj, char.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            Char(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "nd"))
        val char = Char(attrs, null, Position(0))
        assertEquals("nd", char.htmlClass)
    }

    @Test
    fun canAddChildItems() {
        val attrs = Attributes(mapOf("STYLE" to "wj"))
        val char = Char(attrs, null, Position(0, 100))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "words"
        char.add(text)
        assertEquals(1, char.items.size)
    }

    @Test
    fun variousCharStyles() {
        for (styleName in listOf("wj", "nd", "bd", "it", "sc", "add", "qt")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val char = Char(attrs, null, Position(0))
            assertEquals(CharStyle.valueOf(styleName), char.style)
        }
    }
}
