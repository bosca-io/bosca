package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsApprovalTest {
    private val root = "00000000-0000-0000-0000-000000000001"
    private val child = "00000000-0000-0000-0000-000000000002"
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun gate(id: String = "approve", runId: String? = child, type: String = "gate.approval") = buildJsonObject {
        put("nodeId", id); put("runId", runId?.let(::JsonPrimitive) ?: JsonNull); put("type", type); put("name", "Review <release> & continue?")
    }
    private fun run(status: String = "SUSPENDED", nodes: List<JsonObject> = listOf(gate()), id: String = root) = buildJsonObject {
        putJsonObject("pipelines") { putJsonObject("run") {
            put("id", id); put("status", status); put("error", if (status == "FAILED") JsonPrimitive("Gate rejected: Retained note") else JsonNull)
            put("modifiedAt", "2026-11-16T02:00:00Z"); put("awaitingNodes", JsonArray(nodes))
        } }
    }
    private fun result(accepted: Boolean = true) = buildJsonObject { putJsonObject("pipelines") { put("resolveGate", accepted) } }
    private suspend fun loaded() = WorkOpsApprovalModel(root).also { it.refresh(context { _, _ -> run() }) }

    @Test fun `only suspended approval gates are offered and nested gates retain their actual run identity`() = runTest {
        val model = WorkOpsApprovalModel(root)
        model.refresh(context { operation, variables ->
            assertEquals("WorkOpsPipelineApproval", operation); assertEquals(root, variables.getValue("runId").jsonPrimitive.content)
            run(nodes = listOf(gate(), gate("own", null), gate("input", type = "waitForInput"), gate("build", type = "build.wait")))
        })
        assertTrue(model.loaded); assertEquals(listOf(child, root), model.gates.map { it.runId })
        assertEquals("Review <release> & continue?", model.gates.first().prompt)
        model.refresh(context { _, _ -> run("OK") }); assertTrue(model.gates.isEmpty()); assertEquals("Completed", model.statusLabel)
    }

    @Test fun `an absent run and failed reads are distinct and stale actions disappear`() = runTest {
        val model = loaded(); model.refresh(context { _, _ -> error("Run reads denied") })
        assertFalse(model.loaded); assertTrue(model.gates.isEmpty()); assertTrue(model.feedback.failed)
        model.refresh(context { _, _ -> buildJsonObject { putJsonObject("pipelines") { put("run", JsonNull) } } })
        assertTrue(model.loaded); assertNull(model.run); assertFalse(model.feedback.failed)
        model.refresh(context { _, _ -> run(id = child) }); assertFalse(model.loaded); assertTrue(model.feedback.failed)
    }

    @Test fun `an approved decision refreshes its exact child gate before writing and then reads current status`() = runTest {
        val model = loaded(); model.begin(child, "approve", true); val calls = mutableListOf<String>()
        model.decide("  Reviewed by owner  ", true, context { operation, variables ->
            calls += operation
            if (operation == "WorkOpsResolvePipelineApproval") {
                assertEquals(child, variables.getValue("runId").jsonPrimitive.content); assertEquals("approve", variables.getValue("nodeId").jsonPrimitive.content)
                assertTrue(variables.getValue("approved").jsonPrimitive.boolean); assertEquals("Reviewed by owner", variables.getValue("note").jsonPrimitive.content)
                result()
            } else run(if (calls.size == 1) "SUSPENDED" else "OK")
        })
        assertEquals(listOf("WorkOpsPipelineApproval", "WorkOpsResolvePipelineApproval", "WorkOpsPipelineApproval"), calls)
        assertFalse(model.editing); assertFalse(model.feedback.failed); assertEquals("Approval recorded.", model.acknowledgement); assertTrue(model.gates.isEmpty())
    }

    @Test fun `rejected writes retain the captured decision prompt target and note for a deliberate retry`() = runTest {
        val model = loaded(); model.begin(child, "approve", false)
        model.decide("Retained note", true, context { operation, _ -> if (operation == "WorkOpsPipelineApproval") run() else error("Decision denied") })
        assertTrue(model.editing); assertTrue(model.feedback.failed); assertEquals(WorkOpsApprovalDraft(child, "approve", "Review <release> & continue?", false, "Retained note"), model.draft)
        var writes = 0
        model.refresh(context { _, _ -> run() }); assertEquals("Retained note", model.draft.note)
        model.decide(model.draft.note, true, context { operation, variables ->
            if (operation == "WorkOpsResolvePipelineApproval") { writes++; assertFalse(variables.getValue("approved").jsonPrimitive.boolean); result() }
            else run(if (writes == 0) "SUSPENDED" else "FAILED")
        })
        assertEquals(1, writes); assertEquals("Rejection recorded.", model.acknowledgement); assertFalse(model.editing); assertEquals("Gate rejected: Retained note", model.run?.error)
    }

    @Test fun `confirmation is required and resolved or replaced gates cannot receive a stale decision`() = runTest {
        val model = loaded(); model.begin(child, "approve", true)
        model.decide("Keep this draft", false, context { _, _ -> error("Confirmation must precede reads or writes") })
        assertTrue(model.feedback.failed); assertEquals("Keep this draft", model.draft.note)
        for (replacement in listOf(run("OK"), run(nodes = listOf(gate("different"))), run(nodes = listOf(gate(runId = root))))) {
            var reads = 0
            model.decide("Keep this draft", true, context { operation, _ -> assertEquals("WorkOpsPipelineApproval", operation); reads++; replacement })
            assertEquals(1, reads); assertTrue(model.editing); assertTrue(model.feedback.failed); assertTrue(model.feedback.message.startsWith("This approval is no longer waiting."))
            assertEquals(child, model.draft.runId); assertEquals("approve", model.draft.nodeId)
        }
    }

    @Test fun `an acknowledged decision survives a failed readback without offering a duplicate submit`() = runTest {
        val model = loaded(); model.begin(child, "approve", true); var reads = 0
        model.decide("", true, context { operation, _ ->
            if (operation == "WorkOpsResolvePipelineApproval") result()
            else if (++reads == 1) run() else error("Run readback unavailable")
        })
        assertEquals("Approval recorded.", model.acknowledgement); assertFalse(model.editing); assertFalse(model.loaded); assertTrue(model.feedback.failed); assertTrue(model.gates.isEmpty())
        model.decide("", true, context { _, _ -> error("Closed decision must not submit") }); assertFalse(model.editing)
        model.refresh(context { _, _ -> run("OK") }); assertEquals("Approval recorded.", model.acknowledgement); assertTrue(model.loaded)
    }

    @Test fun `an unacknowledged decision and cancellation preserve the draft without claiming success`() = runTest {
        val model = loaded(); model.begin(child, "approve", true)
        model.decide("Keep", true, context { operation, _ -> if (operation == "WorkOpsPipelineApproval") run() else result(false) })
        assertTrue(model.editing); assertEquals("", model.acknowledgement); assertTrue(model.feedback.failed)
        assertFailsWith<CancellationException> { model.decide("Keep", true, context { _, _ -> throw CancellationException("Navigation") }) }
        assertEquals("Keep", model.draft.note); assertEquals("", model.acknowledgement)
    }

    @Test fun `notification links preserve platform run identity and leave Git CI and other destinations unchanged`() {
        assertEquals("/workops/approvals/$root", workOpsNotificationDestination("PIPELINE_APPROVAL_REQUESTED", "/pipelines/runs?runId=$root", null, null))
        assertEquals("/pipelines/$root", workOpsNotificationDestination("PIPELINE_APPROVAL_REQUESTED", "/pipelines/$root", null, null))
        assertEquals("/pipelines/runs?runId=$root", workOpsNotificationDestination("OTHER_EVENT", "/pipelines/runs?runId=$root", null, null))
        assertEquals("/workops/tasks/task", workOpsNotificationDestination("PIPELINE_APPROVAL_REQUESTED", "//external.test", "task", "project"))
        assertEquals("", workOpsNotificationDestination("PIPELINE_APPROVAL_REQUESTED", "https://external.test", null, null))
    }
}
