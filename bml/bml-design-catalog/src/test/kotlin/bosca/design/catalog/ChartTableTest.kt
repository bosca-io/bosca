package bosca.design.catalog

import bosca.design.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ChartTableTest {
    @Test
    fun `text alternatives retain named numeric missing and coordinate values`() {
        val table = ChartTable.from(ChartConfiguration(Json.parseToJsonElement("""{
            "xAxis":{"data":["<Mon>","Tue"]},
            "series":{"name":"Visits","type":"scatter","data":[4,null,{"name":"Wed","value":[3,5,8]}]}
        }""").jsonObject))
        assertEquals("", table.error)
        assertEquals(listOf("<Mon>", "Tue", "Wed"), table.points.map { it.label })
        assertEquals(listOf("4", "No value", "3, 5, 8"), table.points.map { it.value })
        assertEquals(listOf(0, 1, 2), table.points.map { it.index })
        assertTrue(table.points.all { it.dataset == 0 && it.series == "Visits" })
    }

    @Test
    fun `empty and unnamed series have useful text alternatives`() {
        assertEquals(emptyList(), ChartTable.from(ChartConfiguration(JsonObject(emptyMap()))).points)
        val unnamed = ChartTable.from(ChartConfiguration(Json.parseToJsonElement("""{"series":[{"type":"bar","data":[4]}]}""").jsonObject))
        assertEquals("Series 1", unnamed.points.single().series)
    }

    @Test
    fun `catalog native ECharts examples expose each inline series in the table`() {
        for (type in CatalogChart.types) {
            val config = CatalogChart.configuration(type)
            val nativeType = when (type) { "bubble" -> "scatter"; "doughnut", "polarArea" -> "pie"; else -> type }
            assertEquals(nativeType, config.option.getValue("series").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(ChartTable.from(config).points.isNotEmpty(), type)
            assertTrue(ChartTable.from(CatalogChart.configuration(type, empty = true)).points.isEmpty(), type)
        }
    }
    @Test
    fun `world map resources match named data and empty values retain the basemap`() {
        val names = CatalogChart.worldMap.getValue("features").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content
        }.toSet()
        assertTrue(names.containsAll(CatalogChart.mapRegions))
        val chart = CatalogChart("map", type = "map")
        assertEquals(setOf("world"), chart.maps.keys)
        assertEquals(CatalogChart.mapRegions, ChartTable.from(chart.config).points.map { it.label })
        chart.empty = true
        assertTrue(ChartTable.from(chart.config).points.isEmpty())
        assertEquals(setOf("world"), chart.maps.keys)
        chart.chooseType("bar")
        assertTrue(chart.maps.isEmpty())
    }

}
