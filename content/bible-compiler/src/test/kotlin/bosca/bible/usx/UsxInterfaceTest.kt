package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsxInterfaceTest {

    @Test
    fun breakHasEmptyHtmlClass() {
        val brk = Break(Reference("GEN.1.1"), Position(0))
        assertEquals("", brk.htmlClass)
    }

    @Test
    fun breakHasEmptyHtmlAttributes() {
        val brk = Break(null, Position(0))
        assertEquals(true, brk.htmlAttributes.isEmpty())
    }

    @Test
    fun breakToStringWithNewLinesReturnsCarriageReturnLineFeed() {
        val brk = Break(null, Position(0))
        val result = brk.toString(StringContext(includeNewLines = true))
        assertEquals("\r\n", result)
    }

    @Test
    fun breakToStringWithoutNewLinesReturnsEmpty() {
        val brk = Break(null, Position(0))
        val result = brk.toString(StringContext(includeNewLines = false))
        assertEquals("", result)
    }

    @Test
    fun breakReferencePreserved() {
        val ref = Reference("GEN.1.1")
        val brk = Break(ref, Position(0))
        assertEquals(ref, brk.reference)
    }

    @Test
    fun breakNullReference() {
        val brk = Break(null, Position(0))
        assertNull(brk.reference)
    }
}
