package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TableTest {

    @Test
    fun vidFieldPreservation() {
        val attrs = Attributes(mapOf("VID" to "GEN 1:1"))
        val table = Table(attrs, null, Position(0))
        assertEquals("GEN 1:1", table.vid)
    }

    @Test
    fun vidDefaultsToEmptyString() {
        val attrs = Attributes(emptyMap())
        val table = Table(attrs, null, Position(0))
        assertEquals("", table.vid)
    }

    @Test
    fun htmlClassIsEmpty() {
        val attrs = Attributes(emptyMap())
        val table = Table(attrs, null, Position(0))
        assertEquals("", table.htmlClass)
    }

    @Test
    fun canAddRows() {
        val table = Table(Attributes(emptyMap()), null, Position(0, 200))
        val row = Row(Attributes(mapOf("STYLE" to "tr")), null, Position(10, 50))
        table.add(row)
        assertEquals(1, table.items.size)
    }

    @Test
    fun rowStylePreservation() {
        val row = Row(Attributes(mapOf("STYLE" to "tr")), null, Position(0))
        assertEquals("tr", row.style)
    }

    @Test
    fun rowMissingStyleThrowsError() {
        assertFailsWith<IllegalStateException> {
            Row(Attributes(emptyMap()), null, Position(0))
        }
    }

    @Test
    fun rowHtmlClassMatchesStyle() {
        val row = Row(Attributes(mapOf("STYLE" to "tr")), null, Position(0))
        assertEquals("tr", row.htmlClass)
    }

    @Test
    fun tableContentFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "tc1", "ALIGN" to "start"))
        val tc = TableContent(attrs, null, Position(0))
        assertEquals("tc1", tc.style)
        assertEquals("start", tc.align)
    }

    @Test
    fun tableContentColspanOptional() {
        val attrs = Attributes(mapOf("STYLE" to "tc1", "ALIGN" to "start"))
        val tc = TableContent(attrs, null, Position(0))
        assertEquals(null, tc.colspan)
    }

    @Test
    fun tableContentColspanPreserved() {
        val attrs = Attributes(mapOf("STYLE" to "tc1", "ALIGN" to "start", "COLSPAN" to "2"))
        val tc = TableContent(attrs, null, Position(0))
        assertEquals("2", tc.colspan)
    }

    @Test
    fun tableContentMissingStyleThrowsError() {
        assertFailsWith<IllegalStateException> {
            TableContent(Attributes(mapOf("ALIGN" to "start")), null, Position(0))
        }
    }

    @Test
    fun tableContentMissingAlignThrowsError() {
        assertFailsWith<IllegalStateException> {
            TableContent(Attributes(mapOf("STYLE" to "tc1")), null, Position(0))
        }
    }

    @Test
    fun tableContentHtmlClassMatchesStyle() {
        val attrs = Attributes(mapOf("STYLE" to "tc2", "ALIGN" to "end"))
        val tc = TableContent(attrs, null, Position(0))
        assertEquals("tc2", tc.htmlClass)
    }
}
