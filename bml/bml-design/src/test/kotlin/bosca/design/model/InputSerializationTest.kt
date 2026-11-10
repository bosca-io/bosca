package bosca.design.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class InputSerializationTest {
    @Serializable
    private data class SavedWidgets(
        val graph: FlowGraph,
        val chart: ChartConfiguration,
        val layout: List<DashboardLayoutItem>,
    )

    @Test
    fun `generated defaults provide the fields required by browser engines`() {
        assertEquals(Json.parseToJsonElement("""{"nodes":[],"edges":[]}"""),
            Json.encodeToJsonElement(FlowGraph.serializer(), FlowGraph()))
        val node = Json.encodeToJsonElement(FlowNode.serializer(), FlowNode("start")).jsonObject
        assertEquals("default", node.getValue("type").jsonPrimitive.content)
        assertEquals(0.0, node.getValue("position").jsonObject.getValue("x").jsonPrimitive.double)
        assertEquals(0.0, node.getValue("position").jsonObject.getValue("y").jsonPrimitive.double)
        assertEquals(JsonObject(emptyMap()), node["data"])
        assertEquals(JsonObject(emptyMap()),
            Json.encodeToJsonElement(ChartConfiguration.serializer(), ChartConfiguration(JsonObject(emptyMap()))))
        assertEquals(JsonArray(emptyList()), Json.encodeToJsonElement(ListSerializer(DashboardLayoutItem.serializer()), emptyList()))
    }

    @Test
    fun `node payload survives generated serialization while transient engine fields are ignored`() {
        val source = Json.parseToJsonElement("""{
          "id":"start","position":{"x":2,"y":3},"selected":true,"dimensions":{"width":160,"height":80},
          "data":{"label":"Start","inputs":null,"outputs":[{"id":"items","label":"Items"}],
            "payload":{"settings":{"amount":2},"extra":{"native":true}}}
        }""")
        val node = Json.decodeFromJsonElement(FlowNode.serializer(), source)
        assertNull(node.data.inputs)
        assertEquals("items", assertNotNull(node.data.outputs).single().id)
        val encoded = Json.encodeToJsonElement(FlowNode.serializer(), node).jsonObject
        assertEquals(source.jsonObject.getValue("data").jsonObject["payload"], encoded.getValue("data").jsonObject["payload"])
        assertFalse("selected" in encoded || "dimensions" in encoded)
        assertEquals(node, Json.decodeFromString(FlowNode.serializer(), encoded.toString()))
        val edge = Json.decodeFromString(FlowEdge.serializer(),
            """{"id":"loop","source":"start","target":"start","selected":true}""")
        assertEquals(FlowEdge("loop", "start", "start"), edge)
    }

    @Test
    fun `application state uses generated objects and ordinary layout lists`() {
        val state = SavedWidgets(
            FlowGraph(nodes = listOf(FlowNode("start", data = FlowNodeData(payload = buildJsonObject { put("enabled", true) }))),
                edges = listOf(FlowEdge("loop", "start", "start", markerEnd = FlowMarker.Arrow())), viewport = FlowViewport()),
            ChartConfiguration(Json.parseToJsonElement("""{
                "series":[{"type":"line","data":[2,null,{"name":"Day","value":3}]}],
                "legend":{"show":false},"yAxis":{"max":20}
            }""").jsonObject),
            listOf(DashboardLayoutItem("chart", 0, 0, 8, 6)),
        )
        val native = Json.encodeToJsonElement(SavedWidgets.serializer(), state).jsonObject
        assertEquals(Json.encodeToJsonElement(FlowGraph.serializer(), state.graph), native["graph"])
        assertEquals(Json.encodeToJsonElement(ChartConfiguration.serializer(), state.chart), native["chart"])
        assertEquals(Json.parseToJsonElement("""[{"id":"chart","x":0,"y":0,"w":8,"h":6}]"""), native["layout"])
        val chart = native.getValue("chart").jsonObject
        assertEquals(Json.parseToJsonElement("""[2,null,{"name":"Day","value":3}]"""),
            chart.getValue("series").jsonArray.single().jsonObject["data"])
        assertEquals(state, Json.decodeFromString(SavedWidgets.serializer(), native.toString()))
    }

    @Test
    fun `marker variants use native strings and generated arrow objects`() {
        val arrow = FlowMarker.Arrow(color = "#123456")
        val expected = Json.parseToJsonElement("""{"type":"arrowclosed","color":"#123456"}""")
        assertEquals(expected, Json.encodeToJsonElement(FlowMarker.Arrow.serializer(), arrow))
        assertEquals(expected, Json.encodeToJsonElement(FlowMarker.serializer(), arrow))
        assertEquals(arrow, Json.decodeFromJsonElement(FlowMarker.serializer(), expected))
        assertEquals(JsonPrimitive("application-marker"),
            Json.encodeToJsonElement(FlowMarker.serializer(), FlowMarker.Reference("application-marker")))
        assertEquals(FlowMarker.Reference("application-marker"),
            Json.decodeFromString(FlowMarker.serializer(), "\"application-marker\""))
    }

    @Test
    fun `malformed fields and unsupported configuration fail at the serialization boundary`() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(FlowNode.serializer(), """{"data":{}}""") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(FlowNode.serializer(), """{"id":"a","position":{"x":"invalid","y":0}}""") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(FlowNodeData.serializer(), """{"outputs":{}}""") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(FlowMarker.serializer(), "42") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ListSerializer(DashboardLayoutItem.serializer()), """{"items":[]}""") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ChartConfiguration.serializer(), "[]") }
    }
}
