package bosca.design.model

import kotlinx.serialization.json.JsonObject
import bosca.documents.Document
import bosca.documents.DocumentNode
import bosca.documents.DocumentSerializers
import bosca.documents.ParagraphNode
import bosca.content.metadata.model.Document as MetadataDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** An empty ProseMirror document as a Kotlin JSON tree, ready for editor input. */
fun emptyDocument(): JsonObject = documentValue(Document(content = listOf(ParagraphNode())))

private val documentJson = Json { serializersModule = DocumentSerializers; explicitNulls = false }

/**
 * Uses Bosca's shared nodes and marks for typed editor authoring. Keep submitted/custom JSON trees
 * intact: decoding them through the closed shared schema can reject unknown nodes or lose attributes.
 */
fun documentValue(document: Document): JsonObject = documentNodeValue(
    if (document.content.isEmpty()) document.copy(content = listOf(ParagraphNode())) else document,
)

/** Extracts the editor body from a content document; its metadata stays on the server. */
fun documentValue(document: MetadataDocument): JsonObject = when (val body = document.content?.document) {
    null -> emptyDocument()
    is Document -> documentValue(body)
    else -> documentNodeValue(body)
}

private fun documentNodeValue(document: DocumentNode): JsonObject =
    documentJson.encodeToJsonElement(DocumentNode.serializer(), document).jsonObject
