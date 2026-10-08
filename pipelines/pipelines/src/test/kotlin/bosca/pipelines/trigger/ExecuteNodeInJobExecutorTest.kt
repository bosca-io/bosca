@file:OptIn(ExperimentalUuidApi::class, bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.pipelines.trigger

import bosca.pipelines.PipelineContext
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * The generic node-execution backing job: re-runs a suspended node's
 * `execute()` inside a child job and stages the output where the run's resume reads it. This is the
 * one mechanism every `willSuspend = true` node parks on, so it is verified directly: the node's
 * captured inputs are reconstructed, its `execute()` runs, and its value + port land in the result
 * store — and a thrown `execute()` becomes a job failure (no staged output), which the resume routes
 * to the node's error port.
 */
class ExecuteNodeInJobExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val runId = UUID.random()

    private val runService = mockk<PipelineRunService>()
    private val pipelineService = mockk<PipelineService>()
    private val resultStore = mockk<PipelineRunResultStore>(relaxed = true)
    private val securityService = mockk<SecurityService>()
    private val config = mockk<PipelinesRuntimeConfiguration> { every { serviceAccount } returns "sa" }

    private val executor = ExecuteNodeInJobExecutor(runService, pipelineService, resultStore, securityService, config)

    /** A node that echoes its inbound value and routes its output on a named port — enough to prove the round-trip. */
    private class EchoNode(override val id: String, private val fail: Boolean = false) : ActionNode() {
        override val name: String = ""
        override val description: String = ""
        override val position: NodePosition = NodePosition()
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
            if (fail) error("boom")
            return PipelineValue.ofJson(buildJsonObject { put("echo", inputs.first?.encode(context.json) ?: JsonNull) }).onPort("done")
        }
    }

    private class NoOutputNode(override val id: String) : ActionNode() {
        override val name: String = ""
        override val description: String = ""
        override val position: NodePosition = NodePosition()
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = null
    }

    private class AlwaysSuspendNode(override val id: String) : ActionNode() {
        override val name: String = ""
        override val description: String = ""
        override val position: NodePosition = NodePosition()
        override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult = NodeResult.Suspend { }
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = error("not called")
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        coEvery { securityService.getPrincipalByIdentifier(any()) } returns Principal(id = UUID.random())
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()
        coEvery { runService.get(runId) } returns PipelineRun(pipelineId = UUID.random(), graphSnapshot = JsonObject(emptyMap()))
        coEvery { runService.recordNodeAttemptFailure(any(), any(), any()) } returns Unit
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `re-runs the node's execute and stages its value on its port`() = runTest {
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(EchoNode("n")))
        val valueSlot = slot<JsonElement>()
        val portSlot = slot<String?>()
        coEvery { resultStore.put(eq(runId), eq("n"), capture(valueSlot), captureNullable(portSlot)) } returns Unit

        runExecutor(mapOf("in" to JsonPrimitive("hello")))

        assertEquals("done", portSlot.captured)
        assertEquals("hello", valueSlot.captured.jsonObject["echo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a thrown execute fails the job and stages nothing`() = runTest {
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(EchoNode("n", fail = true)))
        val failure = assertFailsWith<IllegalStateException> { runExecutor(emptyMap()) }
        assertEquals("boom", failure.message)
        coVerify(exactly = 1) { runService.recordNodeAttemptFailure(runId, "n", "boom") }
        coVerify(exactly = 0) { resultStore.put(any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation does not record a failed attempt or stage an output`() = runTest {
        val node = mockk<ActionNode>()
        every { node.id } returns "n"
        coEvery { node.run(any(), any()) } throws kotlinx.coroutines.CancellationException("stopping")
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(node))
        assertFailsWith<kotlinx.coroutines.CancellationException> { runExecutor(emptyMap()) }
        coVerify(exactly = 0) { runService.recordNodeAttemptFailure(any(), any(), any()) }
        coVerify(exactly = 0) { resultStore.put(any(), any(), any(), any()) }
    }

    @Test
    fun `a missing node fails the job`() = runTest {
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = emptyList())
        assertFailsWith<IllegalStateException> { runExecutor(emptyMap()) }
    }

    @Test
    fun `a missing run fails before decoding its graph`() = runTest {
        coEvery { runService.get(runId) } returns null

        assertFailsWith<IllegalStateException> { runExecutor(emptyMap()) }

        coVerify(exactly = 0) { pipelineService.decodeGraph(any()) }
    }

    @Test
    fun `a no-output node stages JSON null and repeated work reuses the graph serializer`() = runTest {
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(NoOutputNode("n")))

        runExecutor(emptyMap())
        runExecutor(emptyMap())

        coVerify(exactly = 2) { resultStore.put(runId, "n", JsonNull, null) }
    }

    @Test
    fun `a node that returns suspension from backing work stages JSON null`() = runTest {
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(AlwaysSuspendNode("n")))

        runExecutor(emptyMap())

        coVerify(exactly = 1) { resultStore.put(runId, "n", JsonNull, null) }
    }

    @Test
    fun `runs the backing work under the run's captured principal, not the service account`() = runTest {
        // An on-demand run carries its caller's principal; the backing work must impersonate that
        // principal so the caller's security context traverses the run, rather than the service account.
        val principalId = UUID.random()
        coEvery { runService.get(runId) } returns
            PipelineRun(pipelineId = UUID.random(), graphSnapshot = JsonObject(emptyMap()), principalId = principalId)
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { pipelineService.decodeGraph(any()) } returns PipelineGraph(nodes = listOf(EchoNode("n")))

        runExecutor(emptyMap())

        coVerify(exactly = 1) { securityService.getPrincipalById(principalId) }
        coVerify(exactly = 0) { securityService.getPrincipalByIdentifier(any()) }
    }

    private suspend fun runExecutor(inputs: Map<String, JsonElement>) {
        val jobDef = ExecuteNodeInJob(runId = runId, nodeId = "n", inputs = inputs)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(ExecuteNodeInJob.serializer(), jobDef),
            executor = ExecuteNodeInJobExecutor::class,
        )
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            executor.execute()
        }
    }
}
