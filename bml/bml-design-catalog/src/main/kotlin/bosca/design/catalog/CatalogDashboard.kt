package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Local layout form outcomes; widget content and data belong to the catalog. */
@Serializable
data class CatalogDashboard(
    val id: String,
    var value: String = initial,
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var reject: Boolean = false,
    var visible: Boolean = true,
    var locked: Boolean = true,
    var error: String = "",
    var notice: String = "",
    var noticeSequence: Int = 0,
) {
    suspend fun save(layout: String) {
        delay(150)
        require(Json.parseToJsonElement(layout) is JsonArray) { "Expected layout JSON." }
        if (!reject) value = layout
        error = if (reject) "This example rejected the save. Your layout is retained." else ""
        notice = if (reject) "Layout save rejected." else "Layout saved."
        noticeSequence++
    }
    fun refresh() { notice = "Example refreshed."; noticeSequence++ }
    fun replace() { value = replacement; error = "" }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleRejection() { reject = !reject }
    fun toggleVisible() { visible = !visible }
    fun toggleLocked() { locked = !locked }

    companion object {
        val initial = """[{"id":"visits","x":0,"y":0,"w":8,"h":6,"custom":"retained"},{"id":"notes","x":8,"y":0,"w":4,"h":6},{"id":"summary","x":0,"y":6,"w":12,"h":2}]"""
        val replacement = """[{"id":"visits","x":0,"y":0,"w":6,"h":7,"custom":"replacement"},{"id":"notes","x":6,"y":0,"w":6,"h":7},{"id":"summary","x":0,"y":7,"w":12,"h":2}]"""
    }
}
