package bosca.design.catalog

import bosca.design.model.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartTableTest {
    @Test
    fun `text alternatives retain numeric missing and structured points`() {
        val table = ChartTable.from(ChartConfiguration(ChartType.SCATTER, ChartData(listOf("<Mon>", "Tue"), listOf(
            ChartDataset("Visits", listOf(ChartValue.Number(4.0), ChartValue.Gap, ChartValue.Bubble(3.0, 5.0, 8.0))),
        ))))
        assertEquals("", table.error)
        assertEquals(listOf("<Mon>", "Tue", "3"), table.points.map { it.label })
        assertEquals(listOf("4", "No value", "x: 3, y: 5, r: 8"), table.points.map { it.value })
        assertEquals(listOf(0, 1, 2), table.points.map { it.index })
        assertTrue(table.points.all { it.dataset == 0 && it.series == "Visits" })
    }

    @Test
    fun `empty and unnamed datasets have useful text alternatives`() {
        assertEquals(emptyList(), ChartTable.from(ChartConfiguration(ChartType.BAR)).points)
        val unnamed = ChartTable.from(ChartConfiguration(ChartType.BAR, ChartData(datasets = listOf(ChartDataset(data = listOf(4))))))
        assertEquals("Series 1", unnamed.points.single().series)
    }
}
