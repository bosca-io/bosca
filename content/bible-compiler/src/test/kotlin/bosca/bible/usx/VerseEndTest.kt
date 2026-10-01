package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VerseEndTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("EID" to "GEN 1:1"))
        val ve = VerseEnd(attrs, Reference("GEN.1.1"), Position(0))
        assertEquals("GEN 1:1", ve.eid)
    }

    @Test
    fun missingEidThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            VerseEnd(attrs, null, Position(0))
        }
    }

    @Test
    fun verseIsAlwaysNull() {
        val attrs = Attributes(mapOf("EID" to "GEN 1:1"))
        val ve = VerseEnd(attrs, Reference("GEN.1.1"), Position(0))
        assertNull(ve.verse)
    }

    @Test
    fun htmlClassIsEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1:1"))
        val ve = VerseEnd(attrs, null, Position(0))
        assertEquals("", ve.htmlClass)
    }

    @Test
    fun htmlAttributesAreEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1:1"))
        val ve = VerseEnd(attrs, null, Position(0))
        assertEquals(true, ve.htmlAttributes.isEmpty())
    }

    @Test
    fun toStringReturnsEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1:1"))
        val ve = VerseEnd(attrs, null, Position(0))
        assertEquals("", ve.toString(StringContext()))
    }
}
