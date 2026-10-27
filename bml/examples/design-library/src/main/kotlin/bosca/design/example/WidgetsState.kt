package bosca.design.example

import bosca.design.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import bosca.content.metadata.model.Document as MetadataDocument

/** Application-owned objects for the independent rich-widget form. */
@Serializable
class WidgetsState(val id: String) {
    var document: JsonObject = emptyDocument()
        private set
    /** The loaded record prefills the editor until the application receives a submitted body. */
    val sourceDocument: MetadataDocument? get() = if (saves == 0) MetadataDocuments.article else null
    var graph: FlowGraph = FlowGraph(nodes = listOf(
        FlowNode("start", FlowPosition(), "input", FlowNodeData(label = "Start")),
        FlowNode("finish", FlowPosition(240.0, 0.0), "output", FlowNodeData(label = "Finish")),
    ))
        private set
    var layout: List<DashboardLayoutItem> = listOf(
        DashboardLayoutItem("chart", 0, 0, 8, 6), DashboardLayoutItem("notes", 8, 0, 4, 6),
    )
        private set
    val chart: ChartConfiguration = ChartConfiguration(ChartType.BAR,
        ChartData(listOf("Monday", "Tuesday"), listOf(ChartDataset("Visits", listOf(12, 18)))),
        ChartOptions(animation = false))
    var saves: Int = 0
        private set
    var updates: Int = 0
        private set

    fun save(document: String, graph: String, layout: String) {
        this.document = Json.parseToJsonElement(document).jsonObject
        this.graph = Json.decodeFromString(FlowGraph.serializer(), graph)
        this.layout = Json.decodeFromString(ListSerializer(DashboardLayoutItem.serializer()), layout)
        saves++
    }

    fun refresh() { updates++ }
}
