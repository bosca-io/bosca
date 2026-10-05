package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChapterStartTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("NUMBER" to "3", "SID" to "GEN 3"))
        val cs = ChapterStart(attrs, Reference("GEN.3"), Position(0))
        assertEquals("3", cs.number)
        assertEquals("GEN 3", cs.sid)
    }

    @Test
    fun altNumberAndPubNumberOptional() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1"))
        val cs = ChapterStart(attrs, null, Position(0))
        assertNull(cs.altNumber)
        assertNull(cs.pubNumber)
    }

    @Test
    fun altNumberAndPubNumberPreserved() {
        val attrs = Attributes(mapOf(
            "NUMBER" to "1",
            "SID" to "GEN 1",
            "ALTNUMBER" to "A",
            "PUBNUMBER" to "I"
        ))
        val cs = ChapterStart(attrs, null, Position(0))
        assertEquals("A", cs.altNumber)
        assertEquals("I", cs.pubNumber)
    }

    @Test
    fun missingNumberThrowsError() {
        val attrs = Attributes(mapOf("SID" to "GEN 1"))
        assertFailsWith<IllegalStateException> {
            ChapterStart(attrs, null, Position(0))
        }
    }

    @Test
    fun missingSidThrowsError() {
        val attrs = Attributes(mapOf("NUMBER" to "1"))
        assertFailsWith<IllegalStateException> {
            ChapterStart(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassIsEmpty() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1"))
        val cs = ChapterStart(attrs, null, Position(0))
        assertEquals("", cs.htmlClass)
    }

    @Test
    fun htmlAttributesAreEmpty() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1"))
        val cs = ChapterStart(attrs, null, Position(0))
        assertEquals(true, cs.htmlAttributes.isEmpty())
    }

    @Test
    fun toComponentReturnsNull() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1"))
        val cs = ChapterStart(attrs, null, Position(0))
        assertNull(cs.toComponent(ComponentContext()))
    }

    @Test
    fun toStringReturnsEmpty() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1"))
        val cs = ChapterStart(attrs, null, Position(0))
        assertEquals("", cs.toString(StringContext()))
    }
}
