@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.RunAwaitingNode
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.RunStepKind
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.service.PipelineRunService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Projects the durable [PipelineRun] model onto the `PipelineRunState` GraphQL type — the
 * non-trivial bits are decoding the `awaiting` jsonb to node ids and reading the checkpoint's keys
 * (what an operator sees as "waiting on" vs "completed").
 */
class PipelineRunStateControllerTest {

    private val json = Json
    private val runService = mockk<PipelineRunService>()
    private val controller = PipelineRunStateController(json, runService)

    private fun suspendedAwaiting(vararg nodeAndKey: Pair<String, String>): PipelineRun = PipelineRun(
        id = UUID.random(),
        pipelineId = UUID.random(),
        status = PipelineRunStatus.SUSPENDED,
        graphSnapshot = JsonObject(emptyMap()),
        awaiting = json.encodeToJsonElement(
            ListSerializer(PipelineRunAwait.serializer()),
            nodeAndKey.map { (nodeId, key) -> PipelineRunAwait(nodeId) },
        ),
    )

    @Test
    fun `projects status, awaiting node ids, and completed node ids`() {
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.SUSPENDED,
            graphSnapshot = JsonObject(emptyMap()),
            nodeOutputs = buildJsonObject {
                put("in", JsonNull)
                put("a", JsonPrimitive(1))
            },
            awaiting = json.encodeToJsonElement(
                ListSerializer(PipelineRunAwait.serializer()),
                listOf(PipelineRunAwait("b")),
            ),
        )

        assertEquals(PipelineRunStatus.SUSPENDED, controller.status(run))
        assertEquals(listOf("b"), controller.awaitingNodeIds(run))
        assertEquals(setOf("in", "a"), controller.completedNodeIds(run).toSet())
    }

    @Test
    fun `projects the scalar identity and timing fields`() = runTest {
        val id = UUID.random()
        val pipelineId = UUID.random()
        val created = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z")
        val modified = java.time.OffsetDateTime.parse("2026-06-18T10:05:00Z")
        val run = PipelineRun(
            id = id,
            pipelineId = pipelineId,
            status = PipelineRunStatus.FAILED,
            eventName = "sample.event",
            graphSnapshot = JsonObject(emptyMap()),
            error = "boom",
            createdAt = created,
            modifiedAt = modified,
        )

        assertEquals(id, controller.id(run))
        assertEquals(pipelineId, controller.pipelineId(run))
        assertEquals("sample.event", controller.eventName(run))
        assertEquals("boom", controller.error(run))
        assertEquals(created, controller.createdAt(run))
        assertEquals(modified, controller.modifiedAt(run))
    }

    @Test
    fun `suspended runs expose the latest failed attempt until that node recovers`() = runTest {
        val run = suspendedAwaiting("susp" to "k")
        val now = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z")
        val failed = NodeExecutionRecord(
            runId = run.id, nodeId = "susp", status = NodeExecutionStatus.FAILED,
            startedAt = now, finishedAt = now, durationMs = 0, error = "Repository Edit permission is required",
        )
        coEvery { runService.nodeTimeline(run.id) } returns listOf(failed)
        assertEquals(failed.error, controller.error(run))
        coEvery { runService.nodeTimeline(run.id) } returns listOf(failed, failed.copy(status = NodeExecutionStatus.OK, error = null))
        assertEquals(null, controller.error(run))
        coEvery { runService.nodeTimeline(run.id) } returns listOf(failed.copy(nodeId = "other"))
        assertEquals(null, controller.error(run))
        assertEquals(null, controller.error(run.copy(status = PipelineRunStatus.OK)))
        val input = buildJsonObject { put("repositoryId", "repo") }
        assertEquals(input, controller.input(run.copy(input = input)))
    }

    @Test
    fun `nodes projects the run's per-node timeline`() = runTest {
        val run = suspendedAwaiting("susp" to "k")
        val now = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z")
        coEvery { runService.nodeTimeline(run.id) } returns listOf(
            NodeExecutionRecord(runId = run.id, nodeId = "susp", status = NodeExecutionStatus.SUSPENDED, startedAt = now, finishedAt = now, durationMs = 0),
            NodeExecutionRecord(runId = run.id, nodeId = "susp", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 5),
        )

        val timeline = controller.nodes(run)
        assertEquals(listOf("susp", "susp"), timeline.map { it.nodeId })
        assertEquals(listOf(NodeExecutionStatus.SUSPENDED, NodeExecutionStatus.OK), timeline.map { it.status })
    }

    @Test
    fun `childRuns lists a node's fan-out children via the service, itemIndex projects per child`() = runTest {
        val run = suspendedAwaiting("each" to "k")
        val child = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
            parentRunId = run.id,
            parentNodeId = "each",
            itemIndex = 0,
        )
        coEvery { runService.listChildren(run.id, "each") } returns listOf(child)

        assertEquals(listOf(child.id), controller.childRuns(run, "each").map { it.id })
        assertEquals(0, controller.itemIndex(child))
        assertEquals(null, controller.itemIndex(run), "a top-level run has no item index")
    }

    @Test
    fun `step and awaiting projections retain the child run used by human controls`() = runTest {
        val run = suspendedAwaiting("gate" to "k")
        val childRunId = UUID.random()
        val step = RunStep(
            nodeId = "gate",
            title = "Approve",
            kind = RunStepKind.HUMAN,
            status = RunStepStatus.WAITING,
            depth = 1,
            item = "api",
            runId = childRunId,
            type = "gate.approval",
            channelType = "container",
        )
        coEvery { runService.steps(run.id) } returns listOf(step)
        coEvery { runService.awaitingNodes(run.id) } returns listOf(
            RunAwaitingNode("gate", "gate.approval", "Approve (api)", childRunId),
        )

        assertEquals(listOf(step), controller.steps(run))
        val awaiting = controller.awaitingNodes(run).single()
        assertEquals("gate", awaiting.nodeId)
        assertEquals("gate.approval", awaiting.type)
        assertEquals("Approve (api)", awaiting.name)
        assertEquals(childRunId, awaiting.runId)
    }

    @Test
    fun `empty awaiting and checkpoint project as empty lists`() {
        val run = PipelineRun(
            id = UUID.random(),
            pipelineId = UUID.random(),
            status = PipelineRunStatus.RUNNING,
            graphSnapshot = JsonObject(emptyMap()),
        )

        assertEquals(emptyList(), controller.awaitingNodeIds(run))
        assertEquals(emptyList(), controller.completedNodeIds(run))
    }
}
