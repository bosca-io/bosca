package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsWorkflowsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("workflows") { put(field, value) } } }
    private fun catalog(count: Int = 1, version: Long = 9) = buildJsonObject { putJsonObject("workOps") { putJsonObject("workflows") {
        putJsonArray("all") { repeat(count) { n -> add(buildJsonObject { put("id", "flow-$n"); put("name", "Workflow $n"); put("description", "Notes"); put("initialStateId", JsonNull); put("version", version); putJsonArray("states") {}; putJsonArray("transitions") {} }) } }
        putJsonArray("schemes") { repeat(count) { n -> add(buildJsonObject { put("id", "scheme-$n"); put("name", "Scheme $n"); put("description", JsonNull); put("defaultWorkflowId", "flow-0"); put("version", version) }) } }
    } } }
    private val flow = WorkOpsWorkflowRecord("flow-0", "Stored workflow", "Stored notes", "s1", 7,
        listOf(WorkOpsWorkflowStateRecord("s2", "Done", "DONE", "#1230", 2), WorkOpsWorkflowStateRecord("s1", "Todo", "TODO", "#1230", 0)),
        listOf(WorkOpsWorkflowTransitionRecord("t1", "Finish", "Notes", listOf("s1"), "s2")))
    private val scheme = WorkOpsWorkflowSchemeRecord("scheme-0", "Stored scheme", "Stored notes", flow.id, 4)
    private fun model(kind: String = "workflows") = WorkOpsWorkflowsModel(kind, workflows = listOf(flow), schemes = listOf(scheme), loaded = true)

    @Test fun `catalog paging and result targets remain scoped to workflows or schemes`() = runTest {
        for (kind in listOf("workflows", "schemes")) {
            val target = if (kind == "schemes") "scheme-26" else "flow-26"
            val model = WorkOpsWorkflowsModel(kind, targetId = target)
            model.retry(context { op, _ -> assertEquals("WorkOpsWorkflowCatalog", op); catalog(28) })
            assertTrue(model.loaded); assertEquals(25L, model.offset)
            assertEquals(3, if (kind == "schemes") model.pageSchemes.size else model.pageWorkflows.size)
            assertTrue("tab=$kind" in model.previousHref); assertEquals("", model.nextHref)
            assertEquals(1, model.tabs.count { it.selected })
            model.retry(context { _, _ -> error("Unavailable") }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            model.retry(context { _, _ -> catalog(0) }); assertTrue(model.loaded); assertTrue(model.workflows.isEmpty()); assertTrue(model.schemes.isEmpty())
        }
    }

    @Test fun `workflow edits retain captured version and rejected draft across reload`() = runTest {
        val model = model(); model.open("workflows", flow.id)
        model.reloadDraft(" Unsent workflow ", " Unsent notes ", "", context { _, _ -> catalog(version = 10) })
        assertEquals(7L, model.draft.version); assertEquals(10L, model.workflows.single().version)
        model.save(" Changed workflow ", " Changed notes ", "", context { operation, variables ->
            assertEquals("WorkOpsWorkflowUpdate", operation)
            assertEquals("flow-0", variables.getValue("id").jsonPrimitive.content)
            val input = variables.getValue("input").jsonObject
            assertEquals(setOf("name", "description", "expectedVersion"), input.keys)
            assertEquals("Changed workflow", input.getValue("name").jsonPrimitive.content); assertEquals("Changed notes", input.getValue("description").jsonPrimitive.content)
            assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long); error("Stale workflow")
        })
        assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals(" Changed workflow ", model.draft.name); assertEquals(" Changed notes ", model.draft.description)
    }

    @Test fun `scheme edits preserve inspected revision and selected unavailable default on failed reads`() = runTest {
        val model = model("schemes"); model.open("schemes", scheme.id)
        model.reloadDraft(" Scheme draft ", " Notes ", "removed-flow", context { _, _ -> error("Read denied") })
        assertFalse(model.loaded); assertEquals(4L, model.draft.version)
        assertEquals("Workflow unavailable", model.workflowChoices.single { it.value == "removed-flow" }.label)
        model.retry(context { _, _ -> catalog(version = 12) })
        model.save(" Scheme draft ", " Notes ", flow.id, context { operation, variables ->
            assertEquals("WorkOpsWorkflowSchemeUpdate", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(setOf("name", "description", "defaultWorkflowId", "expectedVersion"), input.keys)
            assertEquals(flow.id, input.getValue("defaultWorkflowId").jsonPrimitive.content); assertEquals(4L, input.getValue("expectedVersion").jsonPrimitive.long)
            error("Stale scheme")
        })
        assertTrue(model.editing); assertEquals(" Scheme draft ", model.draft.name)
    }

    @Test fun `creation closes before postwrite reads fail and retains the correct result link without duplicate retries`() = runTest {
        for (kind in listOf("workflows", "schemes")) {
            val model = model(kind); model.open(kind, ""); var writes = 0
            val operation = if (kind == "schemes") "WorkOpsWorkflowSchemeCreate" else "WorkOpsWorkflowCreate"
            val field = if (kind == "schemes") "createScheme" else "create"
            val ctx = context { op, variables ->
                if (op == operation) {
                    writes++; val input = variables.getValue("input").jsonObject
                    assertEquals("New item", input.getValue("name").jsonPrimitive.content); assertEquals(JsonNull, input["description"] ?: JsonNull)
                    if (kind == "schemes") assertEquals(flow.id, input.getValue("defaultWorkflowId").jsonPrimitive.content)
                    data(field, buildJsonObject { put("id", "created") })
                } else error("Read failed")
            }
            model.save(" New item ", "  ", flow.id, ctx)
            assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed)
            assertTrue("tab=$kind" in model.resultHref); assertTrue("item=created" in model.resultHref)
            model.save("New item", "", flow.id, ctx); assertEquals(1, writes)
        }
    }

    @Test fun `invalid drafts fail before mutation and retain all fields`() = runTest {
        val model = model(); var requests = 0
        val ctx = context { _, _ -> requests++; error("Must not write") }
        model.open("workflows", ""); model.save(" ", "Notes", "", ctx); assertEquals("Enter a name.", model.feedback.message)
        model.open("schemes", ""); model.save("Scheme", "Notes", "", ctx); assertEquals("Choose a default workflow.", model.feedback.message)
        assertEquals("Notes", model.draft.description); assertEquals(0, requests)
        model.loaded = false; model.save("Scheme", "Retained", flow.id, ctx); assertEquals("Reload workflows before saving.", model.feedback.message)
        assertFails { model.open("workflows", flow.id) }
    }

    @Test fun `confirmed deletion retains its captured kind and identity and cannot repeat after accepted write`() = runTest {
        for (kind in listOf("workflows", "schemes")) {
            val model = model(kind); val id = if (kind == "schemes") scheme.id else flow.id
            val field = if (kind == "schemes") "deleteScheme" else "delete"
            val operation = if (kind == "schemes") "WorkOpsWorkflowSchemeDelete" else "WorkOpsWorkflowDelete"
            model.beginDelete(kind, id); model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
            model.delete(true, context { op, variables -> assertEquals(operation, op); assertEquals(setOf("id"), variables.keys); assertEquals(id, variables.getValue("id").jsonPrimitive.content); error("Referenced") })
            assertEquals(id, model.deleting?.id); assertTrue(model.feedback.failed)
            var writes = 0; val ctx = context { op, _ -> if (op == operation) { writes++; data(field, JsonPrimitive(true)) } else error("Read failed") }
            model.delete(true, ctx); assertNull(model.deleting); assertFalse(model.loaded); model.delete(true, ctx); assertEquals(1, writes)
        }
    }

    @Test fun `readonly detail and editor retain ordered states wildcard sources and unavailable labels`() {
        val model = model(); model.view(flow.id); assertSame(flow, model.detail)
        assertEquals(listOf("s1", "s2"), flow.orderedStates.map { it.id })
        assertEquals("Todo", flow.sourceNames(flow.transitions.single()))
        assertEquals("Any state", flow.sourceNames(flow.transitions.single().copy(from = listOf("*"))))
        assertEquals("State unavailable", flow.stateName("missing")); assertEquals("Workflow unavailable", model.workflowName("missing"))
        model.editDetail(); assertNull(model.detail); assertTrue(model.editing); assertSame(flow, model.editedWorkflow)
        model.cancel(); assertFalse(model.editing); model.view(flow.id); model.closeDetail(); assertNull(model.detail)
    }

    @Test fun `cancellation propagates without presenting an empty catalog`() = runTest {
        val model = model(); val ctx = context { _, _ -> throw CancellationException("Navigation") }
        assertFailsWith<CancellationException> { model.retry(ctx) }; assertFalse(model.loaded)
    }
}
