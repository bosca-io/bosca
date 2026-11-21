package bosca.design.catalog

import bosca.design.model.*
import kotlinx.serialization.json.*
import bosca.content.metadata.model.Document as MetadataDocument
import bosca.documents.*
import kotlin.uuid.Uuid

/** Structured values used by standalone component examples. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object CatalogSamples {
    val referenceContainers = listOf(
        DocumentContainerOption("RESOURCE", "Related content", "Metadata chosen through an application adapter", "METADATA", listOf("kind = \"article\"")),
        DocumentContainerOption("SCRIPTURE", "Scripture", "Bible references chosen through an application adapter", "BIBLE"),
    )
    val referenceDocument = Json.parseToJsonElement("""{"type":"doc","content":[
      {"type":"paragraph","content":[{"type":"text","text":"Reference authoring example"}]},
      {"type":"container","attrs":{"name":"RESOURCE","metadataId":"example-article","version":4,"renderer":"card"}},
      {"type":"container","attrs":{"name":"SCRIPTURE","metadataId":"example-bible","references":["GEN.1.1"]}},
      {"type":"bible","attrs":{"metadataIds":["example-bible"],"usfm":["GEN.1.2"]}}
    ]}""").jsonObject
    val documentContainers = listOf(bosca.design.model.DocumentContainerOption("MAIN_CONTENT", "Main content", "Primary document body"))
    val studioDocument = Json.parseToJsonElement("""{
      "type":"doc","content":[
        {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Stored content title"}]},
        {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"A section heading"}]},
        {"type":"container","attrs":{"name":"MAIN_CONTENT","references":["GEN.1"],"metadataId":"related-content","version":4,"renderer":"card"},"content":[
          {"type":"paragraph","content":[{"type":"text","text":"Stored body "},{"type":"mention","attrs":{"id":"profile-reference","label":"An author","entityType":"profile","mentionSuggestionChar":"@"}}]}]},
        {"type":"bible","attrs":{"usfm":["GEN.1.1"],"metadataIds":["scripture-content"]}},
        {"type":"codeBlock","attrs":{"language":"mermaid"},"content":[{"type":"text","text":"graph TD; A-->B;"}]},
        {"type":"paragraph"}
      ]
    }""").jsonObject
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
