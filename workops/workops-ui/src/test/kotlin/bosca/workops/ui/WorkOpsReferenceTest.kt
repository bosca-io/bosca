package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReferenceTest {
    private val id = "11111111-2222-4333-8444-555555555555"
    private fun context(handler: (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), requireNotNull(variables))
    })

    @Test fun `profile reference fallbacks retain handles and every existing visibility label`() = runTest {
        val model = WorkOpsReferenceModel("profile", id)
        for ((visibility, label) in mapOf("USER" to "Private", "PUBLIC" to "Public", "FRIENDS" to "Friends",
            "FRIENDS_OF_FRIENDS" to "Friends of friends", "SYSTEM" to "System")) {
            model.load(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"profile":{"id":"$id","name":"","slug":"handle","visibility":"$visibility"}}}""") })
            assertEquals("handle", model.title)
            assertEquals(label, model.visibilityLabel)
            assertEquals("Profile", model.kindLabel)
            assertEquals("users", model.icon)
        }
        model.load(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"profile":{"id":"$id","name":"","slug":"","visibility":"USER"}}}""") })
        assertEquals("Profile", model.title)
        assertEquals("", model.profileSubtitle)
        model.load(context { _, _ -> Json.parseToJsonElement("""{"profiles":{"profile":null}}""") })
        assertEquals("", model.visibilityLabel)
        assertEquals("Reference unavailable", model.title)
    }

    @Test fun `empty collection and Content documents keep explicit titles and empty body fallbacks`() = runTest {
        val collection = WorkOpsReferenceModel("collection", id)
        collection.load(context { _, _ -> Json.parseToJsonElement("""{"content":{"collections":{"collection":{"id":"$id","name":"","description":null,"items":[]}}}}""") })
        assertEquals("Untitled collection", collection.title)
        assertEquals("Collection", collection.kindLabel)
        assertEquals("folder", collection.icon)
        val content = WorkOpsReferenceModel("metadata", id)
        for (body in listOf("{}", """{"document":{}}""", "null")) {
            content.load(context { _, _ -> Json.parseToJsonElement("""{"content":{"metadata":{"id":"$id","name":"Document","version":1,
              "document":{"title":"Title","content":$body,"template":null},"content":null}}}""") })
            assertNull(content.body)
            assertEquals(emptyList(), content.containers)
        }
    }

    @Test fun `metadata references retain raw document nodes and clear details after access loss`() = runTest {
        val body = Json.parseToJsonElement("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"mention","attrs":{"id":"stored","label":"Author","entityType":"profile"}}]}]}""")
        val response = buildJsonObject { putJsonObject("content") { putJsonObject("metadata") {
            put("id", id); put("version", 2); put("name", "Referenced document")
            putJsonObject("document") { put("title", "Title"); putJsonObject("content") { put("document", body) }
                putJsonObject("template") { putJsonObject("documentTemplate") { putJsonArray("containers") {
                    addJsonObject { put("id", "main"); put("name", "Main content"); put("description", "Body"); put("type", "STANDARD"); put("filters", JsonNull) }
                } } }
            }
            put("content", JsonNull)
        } } }
        val model = WorkOpsReferenceModel("metadata", id, "/workops/tasks/UI-1?tab=history")
        model.load(context { operation, variables ->
            assertEquals("WorkOpsReferenceMetadata", operation); assertEquals(id, variables.getValue("id").jsonPrimitive.content); response
        })
        assertTrue(model.available); assertEquals<JsonElement?>(body, model.body)
        assertEquals("Referenced document", model.title); assertEquals("Document", model.kindLabel)
        assertEquals("/workops/tasks/UI-1?tab=history", model.returnTo)
        assertEquals("", model.downloadHref)
        assertEquals("Main content", model.containers.single().name)
        assertTrue(model.containers.single().filters.isEmpty())
        model.load(context { _, _ -> Json.parseToJsonElement("""{"content":{"metadata":null}}""") })
        assertFalse(model.available); assertNull(model.body); assertEquals("Reference unavailable", model.title)
        assertFalse(model.feedback.failed)
    }

    @Test fun `collection references retain ordered typed children and offset pages`() = runTest {
        val children = (0..25).map { index -> buildJsonObject {
            put("__typename", if (index % 2 == 0) "Metadata" else "Collection")
            put("id", "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"); put("name", if (index == 2) "WORKOPS-REQ-222" else "Child $index")
        } }
        val response = buildJsonObject { putJsonObject("content") { putJsonObject("collections") { putJsonObject("collection") {
            put("id", id); put("name", "Collection"); put("description", "Visible items"); put("items", JsonArray(children))
        } } } }
        val model = WorkOpsReferenceModel("collection", id, "/workops/tasks/UI-1", 25)
        model.load(context { operation, variables ->
            assertEquals("WorkOpsReferenceCollection", operation); assertEquals(25L, variables.getValue("offset").jsonPrimitive.long); response
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals(25, model.items.size); assertTrue(model.hasNext); assertEquals(2L, model.page)
        assertEquals(listOf("metadata", "collection"), model.items.take(2).map { it.kind })
        assertEquals("Child 0", model.items.first().label)
        assertEquals("Requirement", model.items[2].label)
        assertTrue(model.items.first().href.startsWith("/workops/references/metadata/"))
        assertTrue(model.previousHref.endsWith("offset=0")); assertTrue(model.nextHref.endsWith("offset=50"))
    }

    @Test fun `metadata references hide generated tracking names while retaining authored body and names`() = runTest {
        val model = WorkOpsReferenceModel("metadata", id)
        for ((name, label) in listOf("WORKOPS-SPEC-29" to "Specification", "MY_TEAM-REQ-222" to "Requirement",
            id to "Untitled content", "Specification review" to "Specification review")) {
            model.load(context { _, _ -> buildJsonObject { putJsonObject("content") { putJsonObject("metadata") {
                put("id", id); put("version", 1); put("name", name); put("content", JsonNull); put("document", JsonNull)
            } } } })
            assertEquals(label, model.title)
        }
    }

    @Test fun `profile references use current authorized names and handles`() = runTest {
        val model = WorkOpsReferenceModel("profile", id, "https://outside.example/")
        model.load(context { operation, _ ->
            assertEquals("WorkOpsReferenceProfile", operation)
            Json.parseToJsonElement("""{"profiles":{"profile":{"id":"$id","name":"Author","slug":"author","visibility":"PUBLIC"}}}""")
        })
        assertEquals("Author", model.title); assertEquals("@author", model.profileSubtitle)
        assertEquals("Public", model.visibilityLabel); assertEquals("/workops/tasks", model.returnTo)
    }

    @Test fun `file references expose authorized download details and image previews`() = runTest {
        val model = WorkOpsReferenceModel("metadata", id)
        model.load(context { _, _ -> Json.parseToJsonElement("""{"content":{"metadata":{
            "id":"$id","name":"Image","version":1,"document":null,
            "content":{"type":"image/png","length":12,"urls":{"download":{"url":"https://content.example/image?signature=value"}}}
        }}}""") })
        assertEquals("image/png", model.contentType); assertEquals(12L, model.contentLength)
        assertEquals(model.downloadHref, model.imageHref); assertTrue(model.imageHref.startsWith("https://content.example/"))
    }

    @Test fun `unavailable references stay distinct from failures and cancellation is preserved`() = runTest {
        var requests = 0
        val failing = context { _, _ -> requests++; error("Reference service unavailable") }
        for ((kind, value) in listOf("unknown" to id, "metadata" to "bad-id")) {
            val invalid = WorkOpsReferenceModel(kind, value)
            invalid.load(failing); assertFalse(invalid.available); assertFalse(invalid.feedback.failed)
        }
        assertEquals(0, requests)
        val model = WorkOpsReferenceModel("metadata", id)
        model.load(failing); assertTrue(model.feedback.failed); assertEquals("Reference service unavailable", model.feedback.message)
        assertFailsWith<CancellationException> { model.load(context { _, _ -> throw CancellationException("removed") }) }
    }
}
