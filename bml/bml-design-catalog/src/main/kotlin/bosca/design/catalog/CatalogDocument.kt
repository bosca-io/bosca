package bosca.design.catalog

import bosca.documents.*
import bosca.documents.marks.Bold
import bosca.design.model.documentValue
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Local document drafts and save outcomes; no document is published to a product. */
@Serializable
data class CatalogDocument(
    val id: String,
    var value: JsonObject = documentValue(Document(content = listOf(
        HeadingNode(HeadingAttributes(level = 2), content = listOf(TextNode(text = "A shared document"))),
        ParagraphNode(content = listOf(
            TextNode(text = "Write with "), TextNode(text = "familiar formatting", marks = listOf(Bold())),
            TextNode(text = " and save through your own form."),
        )),
    ))),
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
        value = requireNotNull(document)
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
        value = documentValue(Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "Replacement document."))))))
        error = ""
        revision++
    }
}
