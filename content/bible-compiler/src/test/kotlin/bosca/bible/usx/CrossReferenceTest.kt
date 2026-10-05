package bosca.bible.usx

import bosca.bible.CrossReferenceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrossReferenceTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "x", "CALLER" to "+"))
        val cr = CrossReference(attrs, null, Position(0))
        assertEquals(CrossReferenceStyle.x, cr.style)
        assertEquals("+", cr.caller)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(mapOf("CALLER" to "+"))
        assertFailsWith<IllegalStateException> {
            CrossReference(attrs, null, Position(0))
        }
    }

    @Test
    fun missingCallerThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "x"))
        assertFailsWith<IllegalStateException> {
            CrossReference(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleName() {
        val attrs = Attributes(mapOf("STYLE" to "ex", "CALLER" to "-"))
        val cr = CrossReference(attrs, null, Position(0))
        assertEquals("ex", cr.htmlClass)
    }

    @Test
    fun htmlAttributesContainCaller() {
        val attrs = Attributes(mapOf("STYLE" to "x", "CALLER" to "+"))
        val cr = CrossReference(attrs, null, Position(0))
        assertEquals("+", cr.htmlAttributes["data-caller"])
    }

    @Test
    fun toStringExcludedWhenCrossReferencesNotIncluded() {
        val attrs = Attributes(mapOf("STYLE" to "x", "CALLER" to "+"))
        val cr = CrossReference(attrs, null, Position(0, 50))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "ref text"
        cr.add(text)
        val result = cr.toString(StringContext(includeCrossReferences = false))
        assertEquals("", result)
    }

    @Test
    fun toStringIncludedWhenCrossReferencesIncluded() {
        val attrs = Attributes(mapOf("STYLE" to "x", "CALLER" to "+"))
        val cr = CrossReference(attrs, null, Position(0, 50))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "ref text"
        cr.add(text)
        val result = cr.toString(StringContext(includeCrossReferences = true))
        assertEquals("ref text", result)
    }
}
