@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.RunStepKind
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.service.PipelineRunService
import bosca.serialization.UUID
import io.mockk.mockk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field wiring for the run-related GraphQL types backing pipeline observability:
 *  - `PipelineHistoryRun` ([PipelineRunLogWithName]) — append-only run-history rows.
 *  - `PipelineRunState` ([PipelineRun]) — the nullable/derived branches not exercised by
 *    [PipelineRunStateControllerTest] (the non-JsonObject checkpoint and non-JsonArray awaiting).
 *  - `PipelineNodeExecution` ([NodeExecutionRecord]) — one timeline event, incl. its nullable fields.
 *  - `PipelineNodeMetrics` ([NodeMetrics]) — per-node aggregates, incl. both arms of failureRate.
 */
class PipelineRunControllersTest {

    private val json = Json

    @Test
    fun `step and awaiting controllers expose all progress targeting fields`() {
        val runId = UUID.random()
        val step = RunStep(
            "gate", "Approve", RunStepKind.HUMAN, RunStepStatus.WAITING,
            depth = 2, item = "api", runId = runId, type = "gate.approval", channelType = "container",
        )
        val steps = PipelineRunStepController()
        assertEquals("gate", steps.nodeId(step))
        assertEquals("Approve", steps.title(step))
        assertEquals(RunStepKind.HUMAN, steps.kind(step))
        assertEquals(RunStepStatus.WAITING, steps.status(step))
        assertEquals(2, steps.depth(step))
        assertEquals("api", steps.item(step))
        assertEquals(runId, steps.runId(step))
        assertEquals("gate.approval", steps.type(step))
        assertEquals("container", steps.channelType(step))

        val info = AwaitingNodeInfo("gate", "gate.approval", "Approve (api)", runId)
        val awaiting = PipelineAwaitingNodeController()
        assertEquals("gate", awaiting.nodeId(info))
        assertEquals("gate.approval", awaiting.type(info))
        assertEquals("Approve (api)", awaiting.name(info))
        assertEquals(runId, awaiting.runId(info))
    }

    // === PipelineHistoryRunController ============================================================

    private val historyController = PipelineHistoryRunController()

    private fun historyRow(
        runId: UUID? = null,
        finishedAt: java.time.OffsetDateTime? = null,
        durationMs: Long? = null,
        errorMessage: String? = null,
    ) = PipelineRunLogWithName(
        id = UUID.random(),
        pipelineId = UUID.random(),
        runId = runId,
        pipelineName = "Greeter",
        eventName = "sample.event",
        outcome = PipelineRunStatus.OK,
        startedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        finishedAt = finishedAt,
        durationMs = durationMs,
        errorMessage = errorMessage,
    )

    @Test
    fun `history projects the non-null scalar identity, name, outcome, and timing fields`() {
        val started = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z")
        val finished = java.time.OffsetDateTime.parse("2026-06-18T10:01:00Z")
        val runId = UUID.random()
        val row = historyRow(
            runId = runId,
            finishedAt = finished,
            durationMs = 60_000L,
            errorMessage = "boom",
        )

        assertEquals(row.id, historyController.id(row))
        assertEquals(row.pipelineId, historyController.pipelineId(row))
        assertEquals(runId, historyController.runId(row))
        assertEquals("Greeter", historyController.pipelineName(row))
        assertEquals("sample.event", historyController.eventName(row))
        assertEquals(PipelineRunStatus.OK, historyController.outcome(row))
        assertEquals(started, historyController.startedAt(row))
        assertEquals(finished, historyController.finishedAt(row))
        assertEquals(60_000L, historyController.durationMs(row))
        assertEquals("boom", historyController.errorMessage(row))
    }

    @Test
    fun `history projects the nullable fields as null for an inline run still in flight`() {
        val row = historyRow(runId = null, finishedAt = null, durationMs = null, errorMessage = null)

        assertNull(historyController.runId(row))
        assertNull(historyController.finishedAt(row))
        assertNull(historyController.durationMs(row))
        assertNull(historyController.errorMessage(row))
    }

    // === PipelineRunStateController: the remaining nullable/derived branches ======================

    private val runService = mockk<PipelineRunService>()
    private val stateController = PipelineRunStateController(json, runService)

    @Test
    fun `completedNodeIds is empty when the checkpoint is not a JSON object`() {
        // nodeOutputs defaults to JsonNull, which is not a JsonObject -> the `as? JsonObject` null arm.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            nodeOutputs = JsonNull,
        )
        assertEquals(emptyList(), stateController.completedNodeIds(run))
    }

    @Test
    fun `awaitingNodeIds is empty when awaiting is not a JSON array`() {
        // awaiting defaults to JsonNull, which is not a JsonArray -> the `as? JsonArray` null arm.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            awaiting = JsonNull,
        )
        assertEquals(emptyList(), stateController.awaitingNodeIds(run))
    }

    @Test
    fun `completedNodeIds projects the keys of a populated checkpoint object`() {
        // A non-empty JsonObject drives the `as? JsonObject` non-null arm AND the full keys().toList()
        // chain (the complement of the JsonNull null-arm test above).
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            nodeOutputs = JsonObject(mapOf("in" to JsonNull, "n1" to JsonPrimitive(1))),
        )
        assertEquals(setOf("in", "n1"), stateController.completedNodeIds(run).toSet())
    }

    @Test
    fun `completedNodeIds is empty for an empty checkpoint object`() {
        // An empty (but present) JsonObject: `as? JsonObject` non-null, keys empty -> empty list.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            nodeOutputs = JsonObject(emptyMap()),
        )
        assertEquals(emptyList(), stateController.completedNodeIds(run))
    }

    @Test
    fun `completedNodeIds is empty when the checkpoint is a JSON array`() {
        // A JsonArray is a non-JsonNull, non-JsonObject value -> still the `as? JsonObject` null arm,
        // distinct runtime type from the JsonNull case.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            nodeOutputs = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("x"))),
        )
        assertEquals(emptyList(), stateController.completedNodeIds(run))
    }

    @Test
    fun `awaitingNodeIds is empty when awaiting is a JSON object`() {
        // A JsonObject is a non-JsonNull, non-JsonArray value -> the `as? JsonArray` null arm, a
        // distinct runtime type from the JsonNull case.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            awaiting = JsonObject(mapOf("nope" to JsonPrimitive(1))),
        )
        assertEquals(emptyList(), stateController.awaitingNodeIds(run))
    }

    @Test
    fun `awaitingNodeIds decodes a populated awaiting array`() {
        // A real JsonArray drives the `as? JsonArray` non-null arm and the decode/let success path.
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.SUSPENDED,
            graphSnapshot = JsonObject(emptyMap()),
            awaiting = json.encodeToJsonElement(
                ListSerializer(PipelineRunAwait.serializer()),
                listOf(PipelineRunAwait("n1"), PipelineRunAwait("n2")),
            ),
        )
        assertEquals(listOf("n1", "n2"), stateController.awaitingNodeIds(run))
    }

    // === PipelineNodeExecutionController ==========================================================

    private val nodeExecutionController = PipelineNodeExecutionController()

    private fun nodeRecord(
        port: String? = null,
        error: String? = null,
        output: JsonPrimitive? = null,
    ) = NodeExecutionRecord(
        runId = UUID.random(),
        nodeId = "n1",
        status = NodeExecutionStatus.OK,
        startedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        finishedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:01Z"),
        durationMs = 1_000L,
        port = port,
        error = error,
        output = output,
    )

    @Test
    fun `node execution projects the non-null scalar and optional fields`() {
        val output = JsonPrimitive("done")
        val record = nodeRecord(port = "error", error = "boom", output = output)

        assertEquals("n1", nodeExecutionController.nodeId(record))
        assertEquals(NodeExecutionStatus.OK, nodeExecutionController.status(record))
        assertEquals(record.startedAt, nodeExecutionController.startedAt(record))
        assertEquals(record.finishedAt, nodeExecutionController.finishedAt(record))
        assertEquals(1_000L, nodeExecutionController.durationMs(record))
        assertEquals("error", nodeExecutionController.port(record))
        assertEquals("boom", nodeExecutionController.error(record))
        assertEquals(output, nodeExecutionController.output(record))
    }

    @Test
    fun `node execution projects the optional fields as null for a plain success`() {
        val record = nodeRecord(port = null, error = null, output = null)

        assertNull(nodeExecutionController.port(record))
        assertNull(nodeExecutionController.error(record))
        assertNull(nodeExecutionController.output(record))
    }

    // === PipelineNodeMetricsController ============================================================

    private val nodeMetricsController = PipelineNodeMetricsController()

    @Test
    fun `node metrics projects scalars and the non-zero failure rate`() {
        val metrics = NodeMetrics(
            nodeId = "n1",
            executions = 4L,
            failures = 1L,
            p50Ms = 12.5,
            p95Ms = 48.0,
        )

        assertEquals("n1", nodeMetricsController.nodeId(metrics))
        assertEquals(4L, nodeMetricsController.executions(metrics))
        assertEquals(1L, nodeMetricsController.failures(metrics))
        assertEquals(0.25, nodeMetricsController.failureRate(metrics))
        assertEquals(12.5, nodeMetricsController.p50DurationMs(metrics))
        assertEquals(48.0, nodeMetricsController.p95DurationMs(metrics))
    }

    @Test
    fun `node metrics failure rate is zero when there are no executions`() {
        val metrics = NodeMetrics(
            nodeId = "n1",
            executions = 0L,
            failures = 0L,
            p50Ms = 0.0,
            p95Ms = 0.0,
        )
        // The executions == 0L guard arm: avoids a divide-by-zero, returns 0.0.
        assertEquals(0.0, nodeMetricsController.failureRate(metrics))
    }

    // --- PipelineRunUpdate ---

    @Test
    fun `PipelineRunUpdateController exposes a node-level update's fields`() {
        val controller = PipelineRunUpdateController()
        val runId = UUID.random()
        val at = java.time.OffsetDateTime.now()
        val update = PipelineRunUpdate(
            runId = runId, nodeId = "n", nodeStatus = NodeExecutionStatus.OK, port = "out", error = "boom", at = at,
        )
        assertEquals(runId, controller.runId(update))
        assertNull(controller.runStatus(update), "a node-level update carries no run status")
        assertEquals("n", controller.nodeId(update))
        assertEquals(NodeExecutionStatus.OK, controller.nodeStatus(update))
        assertEquals("out", controller.port(update))
        assertEquals("boom", controller.error(update))
        assertEquals(at, controller.at(update))
    }

    @Test
    fun `PipelineRunUpdateController exposes a run-level update's fields`() {
        val controller = PipelineRunUpdateController()
        val update = PipelineRunUpdate(runId = UUID.random(), runStatus = PipelineRunStatus.SUSPENDED)
        assertEquals(PipelineRunStatus.SUSPENDED, controller.runStatus(update))
        assertNull(controller.nodeId(update), "a run-level update carries no node id")
        assertNull(controller.nodeStatus(update))
        assertNull(controller.port(update))
        assertNull(controller.error(update))
    }
}
