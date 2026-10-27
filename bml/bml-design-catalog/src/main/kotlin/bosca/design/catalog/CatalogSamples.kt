package bosca.design.catalog

import bosca.design.model.*
import kotlinx.serialization.json.*
import bosca.content.metadata.model.Document as MetadataDocument
import bosca.documents.*
import kotlin.uuid.Uuid

/** Structured values used by standalone component examples. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object CatalogSamples {
    val metadataDocument = MetadataDocument(
        metadataId = Uuid.parse("00000000-0000-4000-8000-000000000002"),
        version = 3,
        title = "A server-owned title",
        content = Content(Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "A document from Bosca content.")))))),
    )
    val dimensions = buildJsonArray {
        add(buildJsonObject {
            put("component", JsonPrimitive("Button"))
            put("height", JsonPrimitive(32))
        })
        add(buildJsonObject {
            put("component", JsonPrimitive("Compact button"))
            put("height", JsonPrimitive(30))
        })
        add(buildJsonObject {
            put("component", JsonPrimitive("Small button"))
            put("height", JsonPrimitive(24))
        })
    }
    val enabled = buildJsonObject { put("enabled", JsonPrimitive(true)) }
    val textValues = buildJsonObject {
        put("markup", JsonPrimitive("</textarea><script>window.catalogJsonExecuted = true;</script>"))
        put("label", JsonPrimitive("A deliberately long text value that stays inside the editor on a narrow screen"))
    }
    val unsupportedDocument = buildJsonObject {
        put("type", JsonPrimitive("doc"))
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("customBlock"))
                put("attrs", buildJsonObject {
                    put("name", JsonPrimitive("Retain this value"))
                })
            })
        })
    }
    val referenceNotes = listOf(DashboardLayoutItem("notes", 0, 0, 12, 2))
    val referenceContent = listOf(DashboardLayoutItem("content", 0, 0, 12, 2))
}
