package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChapterEndTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("EID" to "GEN 1"))
        val ce = ChapterEnd(attrs, Reference("GEN.1"), Position(0))
        assertEquals("GEN 1", ce.eid)
    }

    @Test
    fun missingEidThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            ChapterEnd(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassIsEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1"))
        val ce = ChapterEnd(attrs, null, Position(0))
        assertEquals("", ce.htmlClass)
    }

    @Test
    fun htmlAttributesAreEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1"))
        val ce = ChapterEnd(attrs, null, Position(0))
        assertEquals(true, ce.htmlAttributes.isEmpty())
    }

    @Test
    fun toComponentReturnsNull() {
        val attrs = Attributes(mapOf("EID" to "GEN 1"))
        val ce = ChapterEnd(attrs, null, Position(0))
        assertNull(ce.toComponent(ComponentContext()))
    }

    @Test
    fun toStringReturnsEmpty() {
        val attrs = Attributes(mapOf("EID" to "GEN 1"))
        val ce = ChapterEnd(attrs, null, Position(0))
        assertEquals("", ce.toString(StringContext()))
    }
}
