package bosca.design.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StructuredInputsTest {
    @Test
    fun `graph edits round trip payload ports and viewport`() {
        val original = Json.parseToJsonElement("""{
          "nodes":[{"id":"start","position":{"x":2,"y":3},"type":"card","draggable":false,
            "data":{"label":"Start","outputs":[{"id":"items","label":"Items"}],"payload":{"enabled":true}}}],
          "edges":[{"id":"loop","source":"start","target":"start","sourceHandle":"items","animated":true}],
          "viewport":{"x":10,"y":20,"zoom":1.5}
        }""").jsonObject
        val graph = Json.decodeFromJsonElement(FlowGraph.serializer(), original)
        assertEquals(FlowPosition(2.0, 3.0), graph.nodes.single().position)
        assertEquals(false, graph.nodes.single().draggable)
        assertEquals(original["nodes"]?.jsonArray?.single()?.jsonObject?.get("data"), Json.encodeToJsonElement(FlowGraph.serializer(), graph).jsonObject["nodes"]?.jsonArray?.single()?.jsonObject?.get("data"))
        assertEquals(graph, Json.decodeFromJsonElement(FlowGraph.serializer(), Json.encodeToJsonElement(FlowGraph.serializer(), graph)))
        assertEquals(FlowViewport(10.0, 20.0, 1.5), graph.viewport)
        assertEquals(graph, Json.decodeFromString(FlowGraph.serializer(), Json.encodeToString(FlowGraph.serializer(), graph)))
    }

    @Test
    fun `layout lists retain native geometry through form changes`() {
        val serializer = ListSerializer(DashboardLayoutItem.serializer())
        val layout = Json.decodeFromString(serializer, """[{"id":"chart","x":0,"y":0,"w":6,"h":4}]""")
        val moved = layout.map { it.copy(x = 2, w = 8) }
        assertEquals(moved, Json.decodeFromString(serializer, Json.encodeToString(serializer, moved)))
        assertEquals(2, Json.encodeToJsonElement(serializer, moved).jsonArray.single().jsonObject.getValue("x").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(serializer, """[{"id":"chart","x":"bad","y":0,"w":6,"h":4}]""") }
    }

    @Test
    fun `Kotlin chart configuration produces native datasets options and accessible table values`() {
        val config = ChartConfiguration(ChartType.BAR, ChartData(listOf("Mon", "Tue"), listOf(
            ChartDataset("Visits", listOf(4, 7), stack = "total", borderColor = "#abcdef"),
        )), options = ChartOptions(plugins = ChartPlugins(legend = ChartLegend(position = ChartLegendPosition.BOTTOM))))
        val native = Json.encodeToJsonElement(ChartConfiguration.serializer(), config).jsonObject
        assertEquals(JsonPrimitive("bar"), native["type"])
        assertEquals(JsonPrimitive("total"), native.getValue("data").jsonObject.getValue("datasets").jsonArray.single().jsonObject["stack"])
        assertEquals(Json.encodeToJsonElement(ChartOptions.serializer(), config.options), native["options"])
        assertEquals(listOf("Mon", "Tue"), ChartTable.from(config).points.map { it.label })
        assertEquals(listOf("4", "7"), ChartTable.from(config).points.map { it.value })
        assertEquals(config, Json.decodeFromString(ChartConfiguration.serializer(), Json.encodeToString(ChartConfiguration.serializer(), config)))
    }

    @Test
    fun `object chart points and JSON strings remain structured data`() {
        val points = listOf(ChartValue.Bubble(1.0, 4.0, 8.0), ChartValue.Gap)
        val config = ChartConfiguration(ChartType.BUBBLE, ChartData(datasets = listOf(ChartDataset("Sizes", points))))
        assertEquals(Json.parseToJsonElement("""[{"x":1,"y":4,"r":8},null]"""), Json.encodeToJsonElement(ChartConfiguration.serializer(), config).jsonObject.getValue("data").jsonObject.getValue("datasets").jsonArray.single().jsonObject["data"])
        val document = emptyDocument()
        assertEquals("doc", document.getValue("type").jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("paragraph"))), document.getValue("content").jsonArray.single())
    }
}
