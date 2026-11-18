package bosca.design.model

import bosca.documents.*
import bosca.documents.marks.Bold
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import kotlinx.serialization.json.*
import kotlin.test.*

class TypedPresentationTest {
    @Test
    fun `native ECharts options retain nested settings and custom values`() {
        val option = Json.parseToJsonElement("""{
            "legend":{"bottom":0},"tooltip":{"trigger":"axis"},
            "xAxis":{"type":"category","data":["Mon","Tue"]},"yAxis":{"type":"value","max":20},
            "series":[{"type":"bar","stack":"total","itemStyle":{"color":"#123456"},
                "data":[4,null,{"name":"Custom","value":[3,5],"note":"retained"}]}]
        }""").jsonObject
        val chart = ChartConfiguration(option)
        val native = Json.encodeToJsonElement(ChartConfiguration.serializer(), chart)
        assertEquals(option, native)
        assertEquals(chart, Json.decodeFromString(ChartConfiguration.serializer(), native.toString()))
        assertEquals(listOf("Mon", "Tue", "Custom"), ChartTable.from(chart).points.map { it.label })
        assertEquals(listOf("4", "No value", "3, 5"), ChartTable.from(chart).points.map { it.value })
    }

    @Test
    fun `typed flow ports styling markers and payload survive browser and state round trips`() {
        val graph = FlowGraph(nodes = listOf(FlowNode("source", type = "card", data = FlowNodeData(label = "Source", tone = "application-accent",
            inputs = emptyList(), outputs = listOf(FlowPort("items", "Items")),
            payload = buildJsonObject { putJsonObject("settings") { put("amount", 2) } }),
            sourcePosition = FlowSide.BOTTOM, draggable = false)),
            edges = listOf(FlowEdge("edge", "source", "source", sourceHandle = "items", animated = true,
                style = FlowEdgeStyle(stroke = "#123456", strokeWidth = 2.0),
                markerStart = FlowMarker.Reference("application-marker"),
                markerEnd = FlowMarker.Arrow(color = "#123456"))))
        val native = Json.encodeToJsonElement(FlowGraph.serializer(), graph).jsonObject
        val data = native.getValue("nodes").jsonArray.single().jsonObject.getValue("data").jsonObject
        assertEquals(JsonArray(emptyList()), data["inputs"])
        assertEquals(JsonPrimitive("items"), data.getValue("outputs").jsonArray.single().jsonObject["id"])
        assertEquals(JsonPrimitive(2), data.getValue("payload").jsonObject.getValue("settings").jsonObject["amount"])
        assertFalse("extra" in data)
        assertEquals(graph, Json.decodeFromJsonElement(FlowGraph.serializer(), native))
        assertEquals(graph, Json.decodeFromString(FlowGraph.serializer(), Json.encodeToString(FlowGraph.serializer(), graph)))
        assertNull(Json.encodeToJsonElement(FlowNodeData.serializer(), FlowNodeData()).jsonObject["outputs"])
        assertEquals(JsonPrimitive("arrowclosed"), native.getValue("edges").jsonArray.single().jsonObject.getValue("markerEnd").jsonObject["type"])
    }

    @Test
    fun `shared document nodes marks and attributes produce editor content`() {
        val value = documentValue(Document(content = listOf(
            HeadingNode(HeadingAttributes(level = 2), content = listOf(TextNode(text = "Title"))),
            ParagraphNode(ParagraphAttributes(textAlign = "center"), content = listOf(
                TextNode(text = "Formatted", marks = listOf(Bold(), Link(LinkAttributes(href = "/documents")))),
            )),
            TaskListNode(content = listOf(TaskItemNode(TaskItemAttributes(checked = true), content = listOf(ParagraphNode())))),
        )))
        assertEquals("doc", value.getValue("type").jsonPrimitive.content)
        val nodes = value.getValue("content").jsonArray
        assertEquals(2, nodes[0].jsonObject.getValue("attrs").jsonObject.getValue("level").jsonPrimitive.int)
        assertEquals("center", nodes[1].jsonObject.getValue("attrs").jsonObject.getValue("textAlign").jsonPrimitive.content)
        assertEquals(listOf("bold", "link"), nodes[1].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("marks").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), nodes[2].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("attrs").jsonObject["checked"])
    }
}
