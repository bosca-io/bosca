@file:OptIn(InternalDI::class)

package bosca.workops.pipeline

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.DryRunTrace
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.service.PipelineRunResultStore
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.JobQueue
import bosca.workops.model.environment.EnvironmentDeployment
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WaitForHealthyNode]: parks the run on a health-wait job and passes the deployment through on resume. */
class WaitForHealthyNodeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }

    private val deploymentId = UUID.random()
    private val deployment = EnvironmentDeployment(
        id = deploymentId, environmentId = UUID.random(), projectId = UUID.random(), versionId = UUID.random(),
    )

    @BeforeTest
    fun setup() = ProviderRegistry.clear()

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun durable(runId: UUID = UUID.random()) =
        PipelineContext(AuthenticationContext(null, null), json, runId = runId)

    private fun inputs() = NodeInputs(mapOf("in" to PipelineValue.of(deployment, EnvironmentDeployment.serializer())))

    private class CapturingResultStore : PipelineRunResultStore {
        val puts = mutableListOf<Triple<UUID, String, JsonElement>>()
        override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
            puts += Triple(runId, nodeId, result)
        }
        override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? = null
        override suspend fun remove(runId: UUID, nodeId: String) {}
    }

    private fun registerQueue(): JobQueue {
        val queue = mockk<JobQueue>(relaxed = true)
        provides<JobQueue>(name = "workops") { queue }
        provides<Json> { json }
        return queue
    }

    @Test
    fun `a durable run parks instead of blocking`() = runTest {
        val result = WaitForHealthyNode(id = "healthy").run(durable(), inputs())
        assertTrue(result is NodeResult.Suspend, "a durable wait should suspend, not block")
    }

    @Test
    fun `the suspend thunk stages the deployment and enqueues the health-wait job`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        val queue = registerQueue()
        val runId = UUID.random()

        val suspend = WaitForHealthyNode(id = "healthy").run(durable(runId), inputs()) as NodeResult.Suspend
        suspend.enqueue()

        assertEquals(1, store.puts.size)
        assertEquals(runId to "healthy", store.puts.single().first to store.puts.single().second)
        coVerify(exactly = 1) { queue.enqueue(any()) }
    }

    @Test
    fun `a non-durable run has nothing to park on and passes the deployment through`() = runTest {
        val context = PipelineContext(AuthenticationContext(null, null), json)
        val result = WaitForHealthyNode(id = "healthy").run(context, inputs())
        assertTrue(result is NodeResult.Output)
        assertEquals(deployment, result.value?.value)
    }

    @Test
    fun `a dry run never parks — it traces and passes through`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(
            AuthenticationContext(null, null),
            json,
            dryRun = true,
            runId = UUID.random(),
            trace = trace,
        )
        val result = WaitForHealthyNode(id = "healthy").run(dry, inputs())
        assertTrue(result is NodeResult.Output)
        assertTrue("healthy" in trace.actions)
    }

    @Test
    fun `public wait execution handles non durable and missing inputs explicitly`() = runTest {
        val node = WaitForHealthyNode(id = "healthy")
        val nonDurable = PipelineContext(AuthenticationContext(null, null), json)
        val regular = node.run(nonDurable, NodeInputs(emptyMap())) as NodeResult.Output
        assertNull(regular.value)
        val dry = node.run(
            PipelineContext(AuthenticationContext(null, null), json, dryRun = true),
            NodeInputs(emptyMap()),
        ) as NodeResult.Output
        assertNull(dry.value)

        val suspended = node.run(durable(), NodeInputs(emptyMap())) as NodeResult.Suspend
        val failure = assertFailsWith<IllegalStateException> {
            suspended.enqueue()
        }
        assertTrue(failure.message.orEmpty().isNotBlank())
    }
}
