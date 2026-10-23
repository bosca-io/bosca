package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Isolated example graphs and form outcomes, independent of pipeline services. */
@Serializable
data class CatalogFlow(
    val id: String,
    var value: String = initial,
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
        require(Json.parseToJsonElement(graph) is JsonObject) { "Expected graph JSON." }
        if (!reject) value = graph
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
    fun replace() { value = """{"nodes":[{"id":"replacement","type":"card","position":{"x":100,"y":100},"data":{"label":"Replacement","inputs":[],"outputs":[]}}],"edges":[]}"""; error = "" }

    companion object {
        val initial = """{"nodes":[{"id":"source","type":"card","sourcePosition":"bottom","position":{"x":60,"y":0},"data":{"label":"Source","description":"Application input","inputs":[],"outputs":[{"id":"items","label":"Items"}],"customValue":"retained"}},{"id":"process","type":"card","sourcePosition":"bottom","targetPosition":"top","position":{"x":60,"y":120},"data":{"label":"Transform","description":"Application action","inputs":[{"id":"items","label":"Items"}],"outputs":[{"id":"result","label":"Result"}]}},{"id":"output","type":"card","targetPosition":"top","position":{"x":60,"y":240},"data":{"label":"Output","status":"Ready","tone":"success","inputs":[{"id":"result","label":"Result"}],"outputs":[]}}],"edges":[{"id":"first","source":"source","sourceHandle":"items","target":"process","targetHandle":"items"}]}"""
    }
}
