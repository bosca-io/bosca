package bosca.bible.usx

import bosca.bible.BookIdentificationCode
import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BookIdentificationTest {

    @Test
    fun codeFieldPreservation() {
        val attrs = Attributes(mapOf("CODE" to "GEN", "STYLE" to "id"))
        val bi = BookIdentification(attrs, null, Position(0))
        assertEquals(BookIdentificationCode.GEN, bi.code)
        assertEquals("id", bi.id)
    }

    @Test
    fun missingCodeThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "id"))
        assertFailsWith<IllegalStateException> {
            BookIdentification(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassIsBookIdentification() {
        val attrs = Attributes(mapOf("CODE" to "MAT", "STYLE" to "id"))
        val bi = BookIdentification(attrs, null, Position(0))
        assertEquals("book-identification", bi.htmlClass)
    }

    @Test
    fun htmlAttributesContainIdAndCode() {
        val attrs = Attributes(mapOf("CODE" to "REV", "STYLE" to "id"))
        val bi = BookIdentification(attrs, null, Position(0))
        val htmlAttrs = bi.htmlAttributes
        assertEquals("id", htmlAttrs["data-id"])
        assertEquals("REV", htmlAttrs["data-code"])
    }

    @Test
    fun defaultStyleIsEmptyWhenMissing() {
        val attrs = Attributes(mapOf("CODE" to "GEN"))
        val bi = BookIdentification(attrs, null, Position(0))
        assertEquals("", bi.id)
    }

    @Test
    fun canAddTextItems() {
        val attrs = Attributes(mapOf("CODE" to "GEN", "STYLE" to "id"))
        val bi = BookIdentification(attrs, null, Position(0, 100))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "Genesis"
        bi.add(text)
        assertEquals(1, bi.items.size)
    }

    @Test
    fun referencePreserved() {
        val ref = Reference("GEN")
        val attrs = Attributes(mapOf("CODE" to "GEN", "STYLE" to "id"))
        val bi = BookIdentification(attrs, ref, Position(0))
        assertEquals(ref, bi.reference)
    }
}
