package bosca.design.catalog

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local document drafts and save outcomes; no document is published to a product. */
@Serializable
data class CatalogDocument(
    val id: String,
    var value: String = """{"type":"doc","content":[{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"A shared document"}]},{"type":"paragraph","content":[{"type":"text","text":"Write with "},{"type":"text","marks":[{"type":"bold"}],"text":"familiar formatting"},{"type":"text","text":" and save through your own form."}]}]}""",
    var saved: String = "",
    var reject: Boolean = false,
    var readOnly: Boolean = false,
    var disabled: Boolean = false,
    var visible: Boolean = true,
    var error: String = "",
    var revision: Int = 0,
    var notice: String = "",
    var noticeSequence: Int = 0,
) {
    suspend fun save(draft: String) {
        delay(150)
        val document = Json.parseToJsonElement(draft) as? JsonObject
        require(document?.get("type")?.jsonPrimitive?.content == "doc") { "Expected a ProseMirror document." }
        value = draft
        error = if (reject) "This example rejected the save. Your draft is retained." else ""
        if (!reject) saved = draft
        notice = if (reject) "Document save rejected." else "Document saved."
        noticeSequence++
    }

    fun refresh() { notice = "Example refreshed."; noticeSequence++ }
    fun toggleRejection() { reject = !reject }
    fun toggleReadOnly() { readOnly = !readOnly }
    fun toggleDisabled() { disabled = !disabled }
    fun toggleVisible() { visible = !visible }
    fun replace() {
        value = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Replacement document."}]}]}"""
        error = ""
        revision++
    }
}
