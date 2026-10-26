package bosca.design.model

import bosca.documents.*
import bosca.documents.marks.Bold
import bosca.documents.marks.Link
import bosca.documents.marks.LinkAttributes
import kotlinx.serialization.json.*
import kotlin.test.*

class TypedPresentationTest {
    @Test
    fun `nested chart options produce native settings and preserve custom points`() {
        val points = listOf(ChartValue.Number(4.0), ChartValue.Gap, ChartValue.Scatter(1.0, 2.0),
            ChartValue.Bubble(3.0, 5.0, 8.0),
            ChartValue.Custom(buildJsonObject { put("date", "2026-10-25"); put("amount", 12) }))
        val chart = ChartConfiguration(ChartType.BUBBLE, ChartData(datasets = listOf(ChartDataset("Visits", points,
            borderColor = "#123456", borderWidth = 2.0, pointRadius = 3.0, type = ChartType.LINE))), ChartOptions(
            animation = false, plugins = ChartPlugins(
                legend = ChartLegend(position = ChartLegendPosition.BOTTOM, labels = ChartLegendLabels(color = "#123456")),
                tooltip = ChartTooltip(enabled = true, mode = ChartInteractionMode.INDEX),
            ), scales = mapOf("y" to ChartScale(beginAtZero = true, title = ChartTitle("Visits"),
                ticks = ChartTicks(stepSize = 2.0), grid = ChartGrid(display = false))),
        ))
        val native = Json.encodeToJsonElement(ChartConfiguration.serializer(), chart).jsonObject
        val options = native.getValue("options").jsonObject
        assertEquals(JsonPrimitive("bottom"), options.getValue("plugins").jsonObject.getValue("legend").jsonObject["position"])
        assertEquals(JsonPrimitive("index"), options.getValue("plugins").jsonObject.getValue("tooltip").jsonObject["mode"])
        assertEquals(JsonPrimitive("Visits"), options.getValue("scales").jsonObject.getValue("y").jsonObject.getValue("title").jsonObject["text"])
        assertFalse("extra" in options || "extra" in options.getValue("plugins").jsonObject)
        assertEquals(Json.parseToJsonElement("""[4,null,{"x":1,"y":2},{"x":3,"y":5,"r":8},{"date":"2026-10-25","amount":12}]"""), native.getValue("data").jsonObject.getValue("datasets").jsonArray.single().jsonObject["data"])
        assertEquals(JsonPrimitive("line"), native.getValue("data").jsonObject.getValue("datasets").jsonArray.single().jsonObject["type"])
        assertEquals(chart, Json.decodeFromString(ChartConfiguration.serializer(), Json.encodeToString(ChartConfiguration.serializer(), chart)))
        assertFailsWith<IllegalArgumentException> { ChartValue.Number(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ChartValue.Bubble(1.0, 2.0, -1.0) }
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
