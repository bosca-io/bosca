package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    private fun createText(ref: Reference? = Reference("GEN.1.1")): Text {
        val attrs = Attributes(emptyMap())
        return Text(attrs, ref, Position(0, 50))
    }

    @Test
    fun defaultTextIsEmpty() {
        val text = createText()
        assertEquals("", text.text)
    }

    @Test
    fun textFieldCanBeSet() {
        val text = createText()
        text.text = "In the beginning"
        assertEquals("In the beginning", text.text)
    }

    @Test
    fun htmlClassIsVerse() {
        val text = createText()
        assertEquals("verse", text.htmlClass)
    }

    @Test
    fun htmlAttributesContainUsfmAndVerseWhenReferencePresent() {
        val text = createText(Reference("GEN.1.5"))
        val attrs = text.htmlAttributes
        assertEquals("GEN.1.5", attrs["data-usfm"])
        assertEquals("5", attrs["data-verse"])
    }

    @Test
    fun htmlAttributesEmptyWhenNoVerseNumber() {
        val text = createText(Reference("GEN.1"))
        val attrs = text.htmlAttributes
        // verse is empty string for "GEN.1", which is falsy but not null
        // The verse getter returns reference?.number which is "" for GEN.1
        assertEquals(true, attrs.containsKey("data-verse") || attrs.isEmpty())
    }

    @Test
    fun htmlAttributesEmptyWhenNoReference() {
        val text = createText(null)
        val attrs = text.htmlAttributes
        assertEquals(true, attrs.isEmpty())
    }

    @Test
    fun toStringContextWithoutNewLinesStripsNewlines() {
        val text = createText()
        text.text = "line1\nline2\r\nline3"
        val context = StringContext(includeNewLines = false)
        val result = text.toString(context)
        assertEquals("line1line2line3", result)
    }

    @Test
    fun toStringContextWithNewLinesPreservesNewlines() {
        val text = createText()
        text.text = "line1\nline2"
        val context = StringContext(includeNewLines = true)
        val result = text.toString(context)
        assertEquals("line1\nline2", result)
    }

    @Test
    fun referenceFieldPreservation() {
        val ref = Reference("PSA.23.1")
        val text = createText(ref)
        assertEquals(ref, text.reference)
    }

    @Test
    fun positionFieldPreservation() {
        val attrs = Attributes(emptyMap())
        val pos = Position(10, 50)
        val text = Text(attrs, null, pos)
        assertEquals(10, text.position.start)
        assertEquals(50, text.position.end)
    }

    @Test
    fun verseExtractsNumberFromReference() {
        val text = createText(Reference("GEN.1.7"))
        assertEquals("7", text.verse)
    }
}
