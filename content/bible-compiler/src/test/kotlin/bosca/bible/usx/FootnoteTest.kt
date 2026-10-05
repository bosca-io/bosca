package bosca.bible.usx

import bosca.bible.FootnoteStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FootnoteTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "f", "CALLER" to "+"))
        val fn = Footnote(attrs, null, Position(0))
        assertEquals(FootnoteStyle.f, fn.style)
        assertEquals("+", fn.caller)
    }

    @Test
    fun categoryOptional() {
        val attrs = Attributes(mapOf("STYLE" to "f", "CALLER" to "+"))
        val fn = Footnote(attrs, null, Position(0))
        assertNull(fn.category)
    }

    @Test
    fun categoryPreserved() {
        val attrs = Attributes(mapOf("STYLE" to "f", "CALLER" to "+", "CATEGORY" to "study"))
        val fn = Footnote(attrs, null, Position(0))
        assertEquals("study", fn.category)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(mapOf("CALLER" to "+"))
        assertFailsWith<IllegalStateException> {
            Footnote(attrs, null, Position(0))
        }
    }

    @Test
    fun missingCallerThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "f"))
        assertFailsWith<IllegalStateException> {
            Footnote(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "fe", "CALLER" to "-"))
        val fn = Footnote(attrs, null, Position(0))
        assertEquals("fe", fn.htmlClass)
    }

    @Test
    fun toStringExcludedWhenFootnotesNotIncluded() {
        val attrs = Attributes(mapOf("STYLE" to "f", "CALLER" to "+"))
        val fn = Footnote(attrs, null, Position(0, 50))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "footnote text"
        fn.add(text)
        val result = fn.toString(StringContext(includeFootNotes = false))
        assertEquals("", result)
    }

    @Test
    fun toStringIncludedWhenFootnotesIncluded() {
        val attrs = Attributes(mapOf("STYLE" to "f", "CALLER" to "+"))
        val fn = Footnote(attrs, null, Position(0, 50))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "footnote text"
        fn.add(text)
        val result = fn.toString(StringContext(includeFootNotes = true))
        assertEquals("footnote text", result)
    }
}
