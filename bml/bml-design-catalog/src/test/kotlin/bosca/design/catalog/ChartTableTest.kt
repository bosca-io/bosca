package bosca.design.catalog

import bosca.design.model.ChartTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartTableTest {
    @Test
    fun `text alternatives retain numeric missing and structured points`() {
        val table = ChartTable.from("""{"type":"scatter","data":{"labels":["<Mon>","Tue"],"datasets":[{"label":"Visits","data":[4,null,{"x":3,"y":5,"r":8}]}]}}""")
        assertEquals("", table.error)
        assertEquals(listOf("<Mon>", "Tue", "3"), table.points.map { it.label })
        assertEquals(listOf("4", "No value", "x: 3, y: 5, r: 8"), table.points.map { it.value })
        assertEquals(listOf(0, 1, 2), table.points.map { it.index })
        assertTrue(table.points.all { it.dataset == 0 && it.series == "Visits" })
    }

    @Test
    fun `invalid configuration keeps an explicit rendering error`() {
        assertTrue(ChartTable.from("invalid").error.isNotEmpty())
        assertTrue(ChartTable.from("[]").error.isNotEmpty())
        assertEquals(emptyList(), ChartTable.from("""{"type":"bar","data":{"datasets":[]}}""").points)
    }
}
