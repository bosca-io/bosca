package bosca.bible.usx

import bosca.bible.ParaStyle
import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParagraphTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        val para = Paragraph(attrs, null, Position(0))
        assertEquals(ParaStyle.p, para.style)
    }

    @Test
    fun vidFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "q1", "VID" to "GEN 1:1"))
        val para = Paragraph(attrs, null, Position(0))
        assertEquals("GEN 1:1", para.vid)
    }

    @Test
    fun vidIsNullWhenMissing() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        val para = Paragraph(attrs, null, Position(0))
        assertNull(para.vid)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            Paragraph(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "q2"))
        val para = Paragraph(attrs, null, Position(0))
        assertEquals("q2", para.htmlClass)
    }

    @Test
    fun addItemToContainer() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        val para = Paragraph(attrs, Reference("GEN.1"), Position(0, 100))
        val text = Text(Attributes(emptyMap()), Reference("GEN.1.1"), Position(5, 20))
        text.text = "verse text"
        para.add(text)
        assertEquals(1, para.items.size)
    }

    @Test
    fun toStringConcatenatesChildItems() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        val para = Paragraph(attrs, null, Position(0, 100))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "Some text here"
        para.add(text)
        val result = para.toString(StringContext())
        assertEquals("Some text here", result)
    }

    @Test
    fun variousParaStylesWork() {
        for (styleName in listOf("p", "m", "q1", "q2", "s1", "b", "d")) {
            val attrs = Attributes(mapOf("STYLE" to styleName))
            val para = Paragraph(attrs, null, Position(0))
            assertEquals(ParaStyle.valueOf(styleName), para.style)
        }
    }
}
