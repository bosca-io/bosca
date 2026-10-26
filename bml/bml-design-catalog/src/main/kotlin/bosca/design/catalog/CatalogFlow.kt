package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import bosca.design.model.*
import kotlinx.serialization.json.*

/** Isolated example graphs and form outcomes, independent of pipeline services. */
@Serializable
data class CatalogFlow(
    val id: String,
    var value: FlowGraph = initial,
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var reject: Boolean = false,
    var visible: Boolean = true,
    var rejectConnections: Boolean = false,
    var error: String = "",
    var notice: String = "",
    var noticeSequence: Int = 0,
) {
    suspend fun save(graph: String) {
        delay(150)
        val submitted = Json.decodeFromString(FlowGraph.serializer(), graph)
        if (!reject) value = submitted
        error = if (reject) "This example rejected the save. Your graph is retained." else ""
        notice = if (reject) "Graph save rejected." else "Graph saved."
        noticeSequence++
    }
    fun refresh() { notice = "Example refreshed."; noticeSequence++ }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleRejection() { reject = !reject }
    fun toggleVisible() { visible = !visible }
    fun toggleConnections() { rejectConnections = !rejectConnections }
    fun replace() {
        value = FlowGraph(nodes = listOf(FlowNode("replacement", FlowPosition(100.0, 100.0), "card",
            FlowNodeData(label = "Replacement", inputs = emptyList(), outputs = emptyList()))))
        error = ""
    }

    companion object {
        val initial = FlowGraph(
            nodes = listOf(
                FlowNode("source", FlowPosition(60.0, 0.0), "card", FlowNodeData(
                    label = "Source", description = "Application input", inputs = emptyList(), outputs = listOf(FlowPort("items", "Items")),
                    payload = buildJsonObject { put("customValue", "retained") },
                ), sourcePosition = FlowSide.BOTTOM),
                FlowNode("process", FlowPosition(60.0, 120.0), "card", FlowNodeData(
                    label = "Transform", description = "Application action", inputs = listOf(FlowPort("items", "Items")), outputs = listOf(FlowPort("result", "Result")),
                ), sourcePosition = FlowSide.BOTTOM, targetPosition = FlowSide.TOP),
                FlowNode("output", FlowPosition(60.0, 240.0), "card", FlowNodeData(
                    label = "Output", status = "Ready", tone = "success", inputs = listOf(FlowPort("result", "Result")), outputs = emptyList(),
                ), targetPosition = FlowSide.TOP),
            ),
            edges = listOf(FlowEdge("first", "source", "process", "items", "items")),
        )
    }
}
