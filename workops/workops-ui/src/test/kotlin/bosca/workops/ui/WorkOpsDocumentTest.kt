package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsDocumentTest {
    private val body = Json.parseToJsonElement("""{"type":"doc","content":[{"type":"heading","attrs":{"level":1}},{"type":"container","attrs":{"name":"main","version":4,"references":["GEN.1"]},"content":[{"type":"paragraph"}]},{"type":"bible","attrs":{"usfm":["GEN.1.1"],"metadataIds":["bible"]}}]}""")
    private val loaded = buildJsonObject {
        putJsonObject("profiles") { putJsonArray("current") { addJsonObject { put("id", "profile"); put("name", "Author") } } }
        putJsonObject("content") { putJsonArray("editable") { add(JsonPrimitive("document")) }; putJsonObject("metadata") {
            put("id", "document"); put("version", 3); put("name", "Document")
            putJsonObject("document") {
                put("title", "Original title"); putJsonObject("content") { put("document", body) }
                putJsonObject("template") { put("id", "template"); put("version", 2); putJsonObject("documentTemplate") {
                    put("schema", "heading block+")
                    putJsonArray("containers") { addJsonObject {
                        put("id", "main"); put("name", "Main content"); put("description", "Primary document body")
                        put("type", "STANDARD"); putJsonArray("filters") { add(JsonPrimitive("document")) }
                    } }
                } }
            }
        } }
    }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })

    @Test fun `readonly documents and invalid body shapes never reach a save mutation`() = runTest {
        val model = WorkOpsDocumentModel("document")
        val noWrites = context { operation, _ -> throw AssertionError("Unexpected write $operation") }
        model.reload(context { _, _ -> Json.parseToJsonElement(loaded.toString().replace("\"editable\":[\"document\"]", "\"editable\":[]")) })
        model.save(body.toString(), "{}", noWrites)
        assertEquals("You do not have permission to edit this document.", model.feedback.message)
        model.reload(context { _, _ -> loaded })
        for (invalid in listOf("{}", "[]", """{"type":"paragraph"}""")) {
            model.save(invalid, "{}", noWrites)
            assertTrue(model.feedback.failed)
            assertEquals("", model.savedRevision)
        }
        for (revision in listOf("""{"client":null}""", """{"client":false}""", """{"client":1.5}""", """{"client":{}}""")) {
            model.save(body.toString(), revision, noWrites)
            assertEquals("Invalid document save revision.", model.feedback.message)
        }
    }

    @Test fun `a rejected Content save retains acknowledgement and nested headings retain visible mention fallbacks`() = runTest {
        val model = WorkOpsDocumentModel("document", savedRevision = "previous")
        model.reload(context { _, _ -> loaded })
        val nested = """{"type":"doc","content":[{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Subtitle"}]},
          {"type":"container","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"mention","attrs":{}},{"type":"hardBreak"},{"type":"text","text":" title"}]}]}]}"""
        model.save(nested, "{}", context { operation, variables ->
            assertEquals("WorkOpsSaveDocument", operation)
            assertEquals("@Mention title", variables.getValue("document").jsonObject.getValue("title").jsonPrimitive.content)
            Json.parseToJsonElement("""{"content":{"metadata":{"setMetadataDocument":false}}}""")
        })
        assertEquals("The document was not saved.", model.feedback.message)
        assertEquals("previous", model.savedRevision)
        model.save("""{"type":"doc","content":[{"type":"paragraph"}]}""", "{}", context { _, variables ->
            assertEquals("Original title", variables.getValue("document").jsonObject.getValue("title").jsonPrimitive.content)
            Json.parseToJsonElement("""{"content":{"metadata":{"setMetadataDocument":true}}}""")
        })
        assertFalse(model.feedback.failed)
        assertEquals("{}", model.savedRevision)
    }

    @Test fun `missing and non-text template expressions use the existing editor schema fallback`() = runTest {
        val model = WorkOpsDocumentModel("document")
        for (schema in listOf("null", "\"\"", "{}")) {
            model.reload(context { _, _ -> Json.parseToJsonElement(loaded.toString().replace("\"schema\":\"heading block+\"", "\"schema\":$schema")) })
            assertEquals("heading block+", model.contentExpression)
        }
    }

    @Test fun `only synthesized headings hide generated tracking titles while authored document bytes remain intact`() = runTest {
        val model = WorkOpsDocumentModel("document")
        val empty = Json.parseToJsonElement("""{"profiles":{"current":[]},"content":{"editable":["document"],"metadata":{"id":"document","version":1,"name":"Document","document":{"title":"REQ TEST-REQ-27","content":{},"template":null}}}}""")
        model.reload(context { _, _ -> empty })
        assertEquals("Requirement", model.body.getValue("content").jsonArray[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        val authored = Json.parseToJsonElement("""{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"REQ TEST-REQ-27"}]},{"type":"paragraph","content":[{"type":"text","text":"Authored body"}]}]}""")
        val original = empty.jsonObject
        val content = original.getValue("content").jsonObject
        val metadata = content.getValue("metadata").jsonObject
        val document = metadata.getValue("document").jsonObject
        model.reload(context { _, _ -> JsonObject(original + ("content" to JsonObject(content + ("metadata" to JsonObject(metadata +
            ("document" to JsonObject(document + ("content" to buildJsonObject { put("document", authored) })))))))) })
        assertEquals(authored, model.body)
        model.save(model.body.toString(), "{}", context { _, variables ->
            val input = variables.getValue("document").jsonObject
            assertEquals("REQ TEST-REQ-27", input.getValue("title").jsonPrimitive.content)
            assertEquals(authored, input.getValue("content").jsonObject.getValue("document"))
            Json.parseToJsonElement("""{"content":{"metadata":{"setMetadataDocument":true}}}""")
        })
        assertFalse(model.feedback.failed)
    }

    @Test fun `document save retains raw nodes template identity and an empty first title and acknowledges only success`() = runTest {
        val model = WorkOpsDocumentModel("document")
        model.reload(context { operation, _ -> assertEquals("WorkOpsDocumentMetadata", operation); loaded })
        assertEquals("document.3", model.room)
        assertEquals("Author", model.collaborator)
        assertEquals("heading block+", model.contentExpression)
        assertEquals("Main content", model.containers.single().name)
        assertEquals(listOf("document"), model.containers.single().filters)
        assertEquals(body, model.body)
        val save = context { operation, variables ->
            assertEquals("WorkOpsSaveDocument", operation)
            assertEquals(3, variables.getValue("version").jsonPrimitive.int)
            val input = variables.getValue("document").jsonObject
            assertEquals("", input.getValue("title").jsonPrimitive.content)
            assertEquals("template", input.getValue("templateMetadataId").jsonPrimitive.content)
            assertEquals(2, input.getValue("templateMetadataVersion").jsonPrimitive.int)
            assertEquals(body, input.getValue("content").jsonObject.getValue("document"))
            Json.parseToJsonElement("""{"content":{"metadata":{"setMetadataDocument":true}}}""")
        }
        model.save(body.toString(), """{"7":2}""", save)
        assertFalse(model.feedback.failed)
        assertEquals("""{"7":2}""", model.savedRevision)
        model.save(body.toString(), """{"7":3}""", context { _, _ -> error("Save rejected") })
        assertTrue(model.feedback.failed)
        assertEquals("Save rejected", model.feedback.message)
        assertEquals("""{"7":2}""", model.savedRevision)
        assertFailsWith<CancellationException> { model.save(body.toString(), "{}", context { _, _ -> throw CancellationException("removed") }) }
    }

    @Test fun `unavailable documents and invalid save revisions leave the editor unacknowledged`() = runTest {
        val model = WorkOpsDocumentModel("document")
        model.reload(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"current":[]},"content":{"editable":[],"metadata":null}}""") })
        assertNull(model.metadata)
        assertTrue(model.feedback.failed)
        model.reload(context { _, _ -> loaded })
        for (revision in listOf("[]", """{"7":-1}""", """{"7":"bad"}""", """{"7":"2"}""", """{"7":9007199254740992}""")) {
            model.save(body.toString(), revision, context { operation, _ -> error("Unexpected $operation") })
            assertTrue(model.feedback.failed)
            assertEquals("", model.savedRevision)
        }
    }

    @Test fun `document title includes visible mention labels without exposing their stored identities`() = runTest {
        val model = WorkOpsDocumentModel("document")
        model.reload(context { _, _ -> loaded })
        val mentioned = Json.parseToJsonElement("""{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[
            {"type":"text","text":"Plan with "},{"type":"mention","attrs":{"id":"internal-profile","label":"Jamie","entityType":"profile"}},
            {"type":"text","text":" and "},{"type":"mention","attrs":{"id":"internal-document","entityType":"metadata"}}
        ]},{"type":"paragraph"}]}""")
        model.save(mentioned.toString(), "{}", context { _, variables ->
            assertEquals("Plan with @Jamie and @Mention", variables.getValue("document").jsonObject.getValue("title").jsonPrimitive.content)
            assertEquals(mentioned, variables.getValue("document").jsonObject.getValue("content").jsonObject.getValue("document"))
            Json.parseToJsonElement("""{"content":{"metadata":{"setMetadataDocument":true}}}""")
        })
        assertFalse(model.feedback.failed, model.feedback.message)
    }

    @Test fun `an empty serialized Content body supplies a heading and body for the initial editor`() = runTest {
        val model = WorkOpsDocumentModel("document")
        model.reload(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"current":[]},"content":{"editable":["document"],"metadata":{"id":"document","version":1,"name":"Document","document":{"title":"New document","content":{},"template":null}}}}""") })
        assertFalse(model.feedback.failed)
        val nodes = model.body.getValue("content").jsonArray
        assertEquals("heading", nodes[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("New document", nodes[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("paragraph", nodes[1].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun `view access renders the stored body without granting document edits`() = runTest {
        val model = WorkOpsDocumentModel("document")
        val readOnly = JsonObject(loaded + ("content" to JsonObject(loaded.getValue("content").jsonObject + ("editable" to JsonArray(emptyList())))))
        model.reload(context { _, _ -> readOnly })
        assertFalse(model.feedback.failed)
        assertFalse(model.canEdit)
        assertEquals(body, model.body)
        model.save(body.toString(), "{}", context { operation, _ -> error("Unexpected $operation") })
        assertTrue(model.feedback.failed)
        assertEquals("You do not have permission to edit this document.", model.feedback.message)
        assertEquals("", model.savedRevision)
    }

    @Test fun `an inaccessible reload clears an old document while a transport failure retains its draft`() = runTest {
        val model = WorkOpsDocumentModel("document")
        model.reload(context { _, _ -> loaded })
        model.reload(context { _, _ -> error("Content unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals(body, model.body)
        model.reload(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"current":[]},"content":{"editable":[],"metadata":null}}""") })
        assertTrue(model.feedback.failed)
        assertNull(model.metadata)
        assertFalse(model.canEdit)
        assertEquals("", model.room)
        model.reload(context { _, _ -> loaded })
        val missingBody = JsonObject(loaded + ("content" to JsonObject(loaded.getValue("content").jsonObject +
            ("metadata" to JsonObject(loaded.getValue("content").jsonObject.getValue("metadata").jsonObject + ("document" to JsonNull))))))
        model.reload(context { _, _ -> missingBody })
        assertTrue(model.feedback.failed)
        assertNull(model.metadata)
    }
}
