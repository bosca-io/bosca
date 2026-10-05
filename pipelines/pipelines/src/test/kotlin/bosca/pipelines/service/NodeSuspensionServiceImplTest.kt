@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.builtin.StatusNode
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.trigger.ExecuteNodeInJob
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeSuspensionServiceImplTest {

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `a non-durable context cannot suspend a node`() = runTest {
        val context = PipelineContext(AuthenticationContext(null, null), Json)

        val failure = assertFailsWith<IllegalStateException> {
            NodeSuspensionServiceImpl().suspendNode(context, StatusNode("status"), NodeInputs(emptyMap()))
        }

        assertTrue(failure.message!!.contains("requires a durable run"))
    }

    @Test
    fun `a durable context captures encoded inputs and enqueues correlated work`() = runTest {
        val captured = slot<Job>()
        val queuedId = UUID.random()
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(capture(captured)) } returns queuedId
        provides<Json> { Json }
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }
        val runId = UUID.random()
        val context = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)
        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("payload"))))

        NodeSuspensionServiceImpl().suspendNode(context, StatusNode("status"), inputs)

        val job = captured.captured
        val definition = Json.decodeFromJsonElement(ExecuteNodeInJob.serializer(), job.getDefinition())
        assertEquals(runId, definition.runId)
        assertEquals("status", definition.nodeId)
        assertEquals(JsonPrimitive("payload"), definition.inputs["in"])
        val correlation = Json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), job.getContext())
        assertEquals(runId, correlation.runId)
        assertEquals("status", correlation.nodeId)
    }
}
