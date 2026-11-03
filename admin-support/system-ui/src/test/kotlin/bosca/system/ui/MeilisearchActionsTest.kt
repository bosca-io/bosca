package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.generated.SystemMeilisearchComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MeilisearchActionsTest {
    private val overview get() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/meilisearch/overview.json")).bufferedReader().use { it.readText() })
    private val settings = Json.parseToJsonElement("""{"rankingRules":["words","typo"],"searchableAttributes":["title"],"filterableAttributes":["category"],"sortableAttributes":["title"],"displayedAttributes":["*"],"stopWords":["the"],"synonyms":[{"word":"phone","synonyms":["mobile"]}]}""")
    private fun embedder(name: String) = Json.parseToJsonElement("""{"name":"$name","source":"userProvided","model":null,"dimensions":2,"documentTemplate":null,"documentTemplateMaxBytes":null,"url":null,"request":null,"response":null,"headers":null}""")
    private fun result(field: String, value: JsonElement) = buildJsonObject { put("meilisearchAdmin", buildJsonObject { put(field, value) }) }
    private fun defaults(operation: String): JsonElement = when (operation) {
        "SystemGetStorageSystemsForMeilisearch" -> Json.parseToJsonElement("""{"storageSystems":{"all":[{"id":"33333333-3333-3333-3333-333333333333","name":"Assigned storage"},{"id":"44444444-4444-4444-4444-444444444444","name":"Available storage"}]}}""")
        "SystemGetMeilisearchIndexSettings" -> result("index", buildJsonObject { put("settings", settings) })
        "SystemGetMeilisearchEmbedders" -> result("index", buildJsonObject { put("settings", buildJsonObject { put("embedders", JsonArray(listOf(embedder("first"), embedder("second")))) }) })
        "SystemGetMeilisearchIndexDocuments" -> result("index", buildJsonObject { put("stats", buildJsonObject { put("numberOfDocuments", 25) }); put("documents", JsonArray(listOf(buildJsonObject { put("id", "fixture-document"); put("title", "Fixture document") }))) })
        else -> overview
    }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement = { operation, _ -> defaults(operation) }) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private suspend fun model() = MeilisearchConsoleModel(true).also { it.refresh(context()); assertFalse(it.feedback.failed, it.feedback.message) }
    private fun task(field: String) = result(field, buildJsonObject { put("uid", 8); put("status", "enqueued") })

    @Test fun `overview detail and document pages preserve selected tabs and editor drafts`() = runTest {
        val model = model(); model.chooseTab("indexes"); model.openIndex("fixture_index", context())
        assertFalse(model.settingsFeedback.failed); assertFalse(model.embedderFeedback.failed); assertFalse(model.documentsFeedback.failed)
        val statsRequest = model.indexOpenRequest; model.chooseIndexTab("documents")
        assertNotEquals(statsRequest, model.indexOpenRequest, "A selected tab renders new drawer content")
        model.loadDocuments(20, context { op, vars -> if (op == "SystemGetMeilisearchIndexDocuments") { assertEquals(20, vars.getValue("offset").jsonPrimitive.int); assertEquals(20, vars.getValue("limit").jsonPrimitive.int) }; defaults(op) })
        val request = model.indexOpenRequest
        model.openEditor("embedder", "first"); model.embedderDraft = model.embedderDraft.copy(documentTemplate = "unsaved template")
        model.refresh(context()); assertEquals("indexes", model.tab); assertEquals("documents", model.indexTab); assertEquals(20, model.documentsOffset)
        assertEquals(request, model.indexOpenRequest); assertEquals("unsaved template", model.embedderDraft.documentTemplate)
        model.loadDocuments(-1, context { _, _ -> error("Unexpected IO") }); assertTrue(model.documentsFeedback.failed); assertEquals(20, model.documentsOffset)
    }
    @Test fun `settings use the SDL input and do not submit read-only synonyms`() = runTest {
        val model = model(); model.openIndex("fixture_index", context())
        model.updateSettings(context { operation, variables ->
            if (operation != "SystemUpdateMeilisearchSettings") return@context defaults(operation)
            val value = variables.getValue("settings").jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive("title"))), value["searchableAttributes"])
            assertEquals(JsonArray(listOf(JsonPrimitive("the"))), value["stopWords"])
            assertFalse("synonyms" in value); task("updateSettings")
        }); assertFalse(model.feedback.failed, model.feedback.message); assertTrue(model.operationNotice.contains("queued"))
        assertEquals(1, model.noticeSequence)
        model.refresh(context()); assertEquals(1, model.noticeSequence, "Polling must not replay the operation toast")
    }
    @Test fun `node credentials and storage assignments retain their resource semantics`() = runTest {
        val model = model(); val id = requireNotNull(model.overview).nodes.single().id; model.openEditor("node", id)
        assertEquals("", model.nodeDraft.key)
        model.saveNode("Changed", "description", "http://localhost:7700", "", true, true, false, context { op, vars ->
            if (op == "SystemEditMeilisearchNode") {
                assertNull(vars.getValue("input").jsonObject["key"]?.takeUnless { it == JsonNull })
                assertEquals(JsonArray(listOf(JsonPrimitive("SEARCH"), JsonPrimitive("VECTOR"))), vars.getValue("input").jsonObject["types"])
                result("editNode", buildJsonObject { put("id", id); put("name", "Changed") })
            } else defaults(op)
        }); assertTrue(model.editorCompleted); assertFalse(model.editorFeedback.failed)
        model.openEditor("node", ""); model.saveNode("Draft", "", "http://localhost:7700", "", false, false, false, context { _, _ -> error("Unexpected IO") })
        assertTrue(model.editorFeedback.failed); assertEquals("Draft", model.nodeDraft.name); assertFalse(model.editorCompleted)
        model.openEditor("assign", id); assertEquals(listOf("Available storage"), model.availableStorage.map { it.name })
        model.assignStorage("33333333-3333-3333-3333-333333333333", context { _, _ -> error("Unexpected IO") }); assertTrue(model.editorFeedback.failed)
    }
    @Test fun `embedder validation retains JSON drafts and updates only the selected provider`() = runTest {
        val model = model(); model.openIndex("fixture_index", context()); model.openEditor("embedder", "first")
        model.saveEmbedder("first", "rest", "", "secret", "2", "draft", "400", "http://localhost:7700", "{invalid", "null", "null", context { _, _ -> error("Unexpected IO") })
        assertTrue(model.editorFeedback.failed); assertEquals("{invalid", model.embedderDraft.request); assertFalse(model.editorCompleted)
        model.saveEmbedder("first", "userProvided", "", "", "2", "", "", "", "{retained inactive draft", "null", "null", context { op, vars ->
            if (op == "SystemUpdateMeilisearchEmbedders") {
                val input = vars.getValue("embedders").jsonArray.single().jsonObject
                assertEquals("first", input.getValue("name").jsonPrimitive.content); assertEquals(2, input.getValue("dimensions").jsonPrimitive.int)
                assertNull(input["key"]?.takeUnless { it == JsonNull }); assertNull(input["request"]?.takeUnless { it == JsonNull }); task("updateEmbedders")
            } else defaults(op)
        }); assertTrue(model.editorCompleted); assertFalse(model.editorFeedback.failed, model.editorFeedback.message)
    }
    @Test fun `changing sources retains inactive REST drafts without submitting their invalid JSON`() = runTest {
        val model = model(); model.openEditor("embedder", "")
        model.changeEmbedderSource("draft", "rest", "", "secret", "2", "", "", "http://localhost", "", "", "")
        model.changeEmbedderSource("draft", "userProvided", "", "secret", "2", "", "", "http://localhost", "{invalid", "[1]", "{}")
        assertEquals("{invalid", model.embedderDraft.request)
        val ctx = RenderContext(); SystemMeilisearchComponent.render(ctx, mapOf("initial" to model)) {}; val html = ctx.writer.toString()
        for (name in listOf("request", "response", "headers")) {
            val native = requireNotNull(Regex("""<textarea[^>]*name="$name"[^>]*>""").find(html)).value
            assertTrue("disabled" in native, "$name must not block unrelated source forms")
        }
        model.changeEmbedderSource("draft", "rest", "", "secret", "2", "", "", "http://localhost", "", "", "")
        assertEquals("{invalid", model.embedderDraft.request); assertEquals("[1]", model.embedderDraft.response)
        assertEquals("{}", model.embedderDraft.headers)
    }
    @Test fun `resource-specific destructive actions require confirmation and reflect mutation results`() = runTest {
        val cases = listOf(Triple("deleteNode", "SystemDeleteMeilisearchNode", "deleteNode"), Triple("deleteIndex", "SystemDeleteMeilisearchIndex", "deleteIndex"), Triple("clearDocuments", "SystemDeleteAllMeilisearchDocuments", "deleteAllDocuments"), Triple("removeStorage", "SystemRemoveMeilisearchNodeFromStorage", "removeNodeFromStorageSystem"), Triple("removeEmbedder", "SystemRemoveMeilisearchEmbedder", "removeEmbedder"), Triple("cancelTask", "SystemCancelMeilisearchTasks", "cancelTasks"))
        for ((kind, operation, field) in cases) {
            val model = model(); model.openIndex("fixture_index", context()); model.prepareOperation(kind, if (kind == "cancelTask") "7" else "fixture_index", "first")
            model.confirmOperation(false, context { _, _ -> error("Unexpected IO") }); assertTrue(model.feedback.failed); assertFalse(model.operationCompleted)
            model.confirmOperation(true, context { op, variables ->
                if (op == operation) {
                    if (kind == "cancelTask") assertEquals(JsonArray(listOf(JsonPrimitive(7))), variables["uids"])
                    if (kind in setOf("deleteNode", "removeStorage")) result(field, JsonPrimitive(true)) else task(field)
                } else defaults(op)
            }); assertTrue(model.operationCompleted, "$kind: ${model.feedback.message}"); assertFalse(model.feedback.failed, model.feedback.message)
        }
        val model = model(); model.openIndex("fixture_index", context()); model.embedders = model.embedders.take(1); model.prepareOperation("removeEmbedder", "fixture_index", "first")
        model.confirmOperation(true, context { op, _ -> if (op == "SystemResetMeilisearchEmbedders") task("resetEmbedders") else defaults(op) }); assertTrue(model.operationCompleted)
        model.prepareOperation("deleteNode", "node", ""); model.confirmOperation(true, context { _, _ -> result("deleteNode", JsonPrimitive(false)) }); assertTrue(model.feedback.failed); assertFalse(model.operationCompleted)
    }
    @Test fun `successful writes remain completed when follow-up reads fail and cancellation propagates`() = runTest {
        val model = model(); model.prepareOperation("deleteIndex", "fixture_index", "")
        model.confirmOperation(true, context { op, _ -> if (op == "SystemDeleteMeilisearchIndex") task("deleteIndex") else error("Refresh unavailable") })
        assertTrue(model.operationCompleted); assertTrue(model.feedback.failed); assertEquals("Refresh unavailable", model.feedback.message)
        val reader = MeilisearchConsoleModel(false, operationKind = "deleteIndex", operationResource = "fixture_index")
        reader.confirmOperation(true, context { _, _ -> error("Unexpected IO") }); assertEquals("Administrator access is required.", reader.feedback.message)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException() }) }
    }
    @Test fun `compiled views use typed tabs tables drawers forms and structured values`() = runTest {
        val model = model(); model.openIndex("fixture_index", context()); model.openEditor("embedder", "first")
        val ctx = RenderContext(); SystemMeilisearchComponent.render(ctx, mapOf("initial" to model)) {}; val html = ctx.writer.toString()
        for (text in listOf("Server Overview", "Nodes (1)", "Indexes (1)", "Recent Tasks (2)", "API Keys (1)", "Fixture node", "Assigned storage", "Fixture failure", "invalid_document_id", "Template Max Bytes", "Delete All Docs", "Request Mapping", "meili-index-panel-documents", "data-placement=\"right\"", "Next</span>", "1 – 1 of 25")) assertTrue(text in html, text)
        assertFalse("Review the resource identifiers" in html); assertFalse("fixture-private-key" in html.substringBefore("data-bml-state-key"))
        assertEquals(Json.parseToJsonElement("""{"id":1}"""), systemMeilisearchDocument(JsonPrimitive("""{"id":1}""")))
        assertEquals("—" to "", systemDateParts(null)); assertEquals("invalid" to "", systemDateParts("invalid"))
    }
}
