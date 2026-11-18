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
    fun `native ECharts configuration preserves multiple axes and mixed series`() {
        val option = Json.parseToJsonElement("""{
            "xAxis":[{"data":["Mon","Tue"]},{"data":["A"]}],"yAxis":{"type":"value"},
            "series":[{"type":"bar","name":"Visits","data":[4,7]},
                {"type":"line","name":"Other","xAxisIndex":1,"data":[12]}]
        }""").jsonObject
        val config = ChartConfiguration(option)
        assertEquals(option, Json.encodeToJsonElement(ChartConfiguration.serializer(), config))
        assertEquals(listOf("Mon", "Tue", "A"), ChartTable.from(config).points.map { it.label })
        assertEquals(listOf(0, 0, 1), ChartTable.from(config).points.map { it.dataset })
        assertEquals(config, Json.decodeFromString(ChartConfiguration.serializer(), option.toString()))
    }
}
