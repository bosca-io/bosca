package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MilestoneTest {

    @Test
    fun fieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "GEN 1:1", "EID" to "GEN 1:2"))
        val ms = Milestone(attrs, null, Position(0))
        assertEquals("ts", ms.style)
        assertEquals("GEN 1:1", ms.sid)
        assertEquals("GEN 1:2", ms.eid)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(mapOf("SID" to "x", "EID" to "y"))
        assertFailsWith<IllegalStateException> {
            Milestone(attrs, null, Position(0))
        }
    }

    @Test
    fun missingSidThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "EID" to "y"))
        assertFailsWith<IllegalStateException> {
            Milestone(attrs, null, Position(0))
        }
    }

    @Test
    fun missingEidThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "x"))
        assertFailsWith<IllegalStateException> {
            Milestone(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyle() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "x", "EID" to "y"))
        val ms = Milestone(attrs, null, Position(0))
        assertEquals("ts", ms.htmlClass)
    }

    @Test
    fun htmlAttributesAreEmpty() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "x", "EID" to "y"))
        val ms = Milestone(attrs, null, Position(0))
        assertTrue(ms.htmlAttributes.isEmpty())
    }

    @Test
    fun toComponentReturnsNull() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "x", "EID" to "y"))
        val ms = Milestone(attrs, null, Position(0))
        assertNull(ms.toComponent(ComponentContext()))
    }

    @Test
    fun toStringReturnsEmpty() {
        val attrs = Attributes(mapOf("STYLE" to "ts", "SID" to "x", "EID" to "y"))
        val ms = Milestone(attrs, null, Position(0))
        assertEquals("", ms.toString(StringContext()))
    }
}
