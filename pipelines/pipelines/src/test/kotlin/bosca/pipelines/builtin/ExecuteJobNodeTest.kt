@file:OptIn(Internal::class)

package bosca.pipelines.builtin

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.testutil.executeForTest
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ExecuteJobNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private val input = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
    private val inputs get() = NodeInputs(mapOf("in" to input))

    private class DummyExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    /**
     * Captures the configuration the node hands off. The durable-await path now `prepare`s the backing
     * job and enqueues it on [queue] (attaching it to the run job, of which there is none in these unit
     * tests), so the config is captured in [prepare]; fire-and-forget goes through [enqueue] → [prepare].
     */
    private class FakeEnqueuer : JobConfigurationEnqueuer {
        override val queueName = "test"
        var enqueuedConfiguration: JsonElement? = null

        override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
            enqueuedConfiguration = configuration
            val job = InternalJobConstructor(configuration, DummyExecutor::class)
            job.initializer()
            return job
        }

        override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
            prepare(configuration, initializer)

        override suspend fun enqueueLater(configuration: JsonElement, timeout: Duration, initializer: suspend Job.() -> Unit): Job =
            enqueue(configuration, initializer)

        // A relaxed fake: the durable-await path enqueues the prepared backing job here; no broker needed.
        override suspend fun queue(): JobQueue = mockk(relaxed = true)
    }

    private fun register(jobName: String): FakeEnqueuer {
        val enqueuer = FakeEnqueuer()
        provides<JobConfigurationEnqueuer>(name = jobName) { enqueuer }
        return enqueuer
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `fire-and-forget enqueues the inbound value as configuration and outputs nothing`() = runTest {
        val enqueuer = register("notify")
        val node = ExecuteJobNode(id = "job", jobName = "notify")
        val result = node.executeForTest(context, inputs)
        assertNull(result)
        assertEquals(input.encode(Json), enqueuer.enqueuedConfiguration)
    }

    @Test
    fun `a durable awaited run suspends instead of holding a worker`() = runTest {
        // runId present (durable) + awaitCompletion -> park the run; the executor/service will persist
        // and run the enqueue thunk, so no registry is needed to observe the suspend.
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Suspend, "a durable awaited run should suspend, not block")
    }

    @Test
    fun `an awaited run with no runId fails — there is no synchronous wait`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        // context get() has no runId -> awaiting cannot park, and there is no synchronous fallback.
        val failure = assertFailsWith<IllegalStateException> { node.run(context, inputs) }
        assertTrue(
            "durable run" in (failure.message ?: ""),
            "expected the durable-run requirement, got: ${failure.message}",
        )
    }

    @Test
    fun `an awaited named node reports its name when the run is not durable`() = runTest {
        val node = ExecuteJobNode(id = "job", name = "Notifier", jobName = "notify", awaitCompletion = true)

        val failure = assertFailsWith<IllegalStateException> { node.run(context, inputs) }

        assertTrue(failure.message.orEmpty().contains("Notifier"))
    }

    @Test
    fun `a dry awaited run never suspends and passes the value through`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random())
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs must not suspend")
        assertEquals(input.value, (result as NodeResult.Output).value?.value)
    }

    @Test
    fun `a durable awaited run stashes its inbound value and enqueues the job`() = runTest {
        // Drive the suspend's deferred thunk: a gate stashes its inbound value (its success output)
        // into the result store and enqueues the backing job with the inbound value as config.
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        val enqueuer = register("notify")
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val runId = UUID.random()
        val context = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)

        val suspend = node.run(context, inputs) as NodeResult.Suspend
        suspend.enqueue()

        assertEquals(1, store.puts.size)
        assertEquals(runId to "job", store.puts.single().first to store.puts.single().second)
        assertEquals(input.encode(Json), store.puts.single().third)
        assertEquals(input.encode(Json), enqueuer.enqueuedConfiguration)
    }

    private class CapturingResultStore : PipelineRunResultStore {
        val puts = mutableListOf<Triple<UUID, String, JsonElement>>()
        override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
            puts += Triple(runId, nodeId, result)
        }
        override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? = null
        override suspend fun remove(runId: UUID, nodeId: String) {}
    }

    @Test
    fun `dry run records the would-be enqueue and passes the value through when awaiting`() = runTest {
        val enqueuer = register("notify")
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val result = node.executeForTest(dryContext, inputs)
        assertEquals(input.value, result?.value, "an awaiting gate should pass the value through in dry runs")
        assertTrue("job" in trace.actions, "the would-be enqueue should be traced")
        assertNull(enqueuer.enqueuedConfiguration, "dry runs must not enqueue")
    }

    @Test
    fun `a durable fire-and-forget run does not suspend`() = runTest {
        val enqueuer = register("notify")
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = false)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Output, "fire-and-forget must not suspend even when durable")
        assertNull((result as NodeResult.Output).value, "fire-and-forget produces no output")
        assertEquals(input.encode(Json), enqueuer.enqueuedConfiguration)
    }

    @Test
    fun `a durable awaited run with no inbound value enqueues empty config and stages json null`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        val enqueuer = register("notify")
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())

        val suspend = node.run(durable, NodeInputs(emptyMap())) as NodeResult.Suspend
        suspend.enqueue()

        // Always-stage: with no inbound value the node stages JSON null (so the resume always finds the
        // staged output present), and the enqueued config is an empty object.
        assertEquals(1, store.puts.size)
        assertEquals(JsonNull, store.puts.single().third)
        assertEquals(emptyJsonObject(), enqueuer.enqueuedConfiguration, "the empty config is an empty json object")
    }

    @Test
    fun `dry run fire-and-forget traces the enqueue and returns nothing`() = runTest {
        val enqueuer = register("notify")
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = false)
        val result = node.executeForTest(dryContext, inputs)
        assertNull(result, "a fire-and-forget action is a sink even in dry runs")
        assertTrue("job" in trace.actions, "the would-be enqueue should still be traced")
        assertNull(enqueuer.enqueuedConfiguration, "dry runs must not enqueue")
    }

    @Test
    fun `dry run with no trace still passes the value through when awaiting`() = runTest {
        val dryContext = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        val result = node.executeForTest(dryContext, inputs)
        assertEquals(input.value, result?.value, "the gate passes the value through even with no trace recorder")
    }

    @Test
    fun `dry run with no inbound value records an empty params object`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = false)
        val result = node.executeForTest(dryContext, NodeInputs(emptyMap()))
        assertNull(result)
        val action = trace.actions["job"] as JsonObject
        assertEquals(emptyJsonObject(), action["params"], "no inbound value yields an empty params object")
    }

    @Test
    fun `fire-and-forget with no inbound value enqueues an empty config`() = runTest {
        val enqueuer = register("notify")
        val node = ExecuteJobNode(id = "job", jobName = "notify")
        val result = node.executeForTest(context, NodeInputs(emptyMap()))
        assertNull(result)
        assertEquals(emptyJsonObject(), enqueuer.enqueuedConfiguration)
    }

    private fun emptyJsonObject(): JsonElement = buildJsonObject { }

    // ----- synthetic default-args constructor: distinct PARTIAL subsets of the optional args -----

    @Test
    fun `constructs with only the required id and jobName and reads every defaulted field`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify")
        assertEquals("job", node.id)
        assertEquals("notify", node.jobName)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertFalse(node.awaitCompletion)
        assertEquals(600, node.awaitTimeoutSeconds)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with id jobName plus name leaving the rest default`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", name = "Notifier")
        assertEquals("Notifier", node.name)
        assertEquals("", node.description)
        assertFalse(node.awaitCompletion)
        assertEquals(600, node.awaitTimeoutSeconds)
    }

    @Test
    fun `constructs with id jobName plus description leaving the rest default`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", description = "enqueues a notification")
        assertEquals("enqueues a notification", node.description)
        assertEquals("", node.name)
        assertFalse(node.awaitCompletion)
    }

    @Test
    fun `constructs with id jobName plus awaitCompletion leaving the rest default`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitCompletion = true)
        assertTrue(node.awaitCompletion)
        assertEquals(600, node.awaitTimeoutSeconds)
        assertEquals("", node.name)
    }

    @Test
    fun `constructs with id jobName plus awaitTimeoutSeconds leaving the rest default`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", awaitTimeoutSeconds = 42)
        assertEquals(42, node.awaitTimeoutSeconds)
        assertFalse(node.awaitCompletion)
        assertEquals("", node.name)
    }

    @Test
    fun `constructs with an explicit position leaving the rest default`() = runTest {
        val node = ExecuteJobNode(id = "job", jobName = "notify", position = NodePosition(x = 5.0, y = 9.0))
        assertEquals(NodePosition(x = 5.0, y = 9.0), node.position)
        assertEquals(5.0, node.position.x)
        assertEquals(9.0, node.position.y)
        assertFalse(node.awaitCompletion)
        assertEquals(600, node.awaitTimeoutSeconds)
    }

    // ----- @Serializable: missing-required-field + full round-trip -----

    @Test
    fun `decoding JSON missing the required id throws a serialization exception`() = runTest {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ExecuteJobNode.serializer(), """{"jobName":"notify"}""")
        }
    }

    @Test
    fun `decoding JSON missing the required jobName throws a serialization exception`() = runTest {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ExecuteJobNode.serializer(), """{"id":"job"}""")
        }
    }

    @Test
    fun `decoding JSON with every field present applies the provided values`() = runTest {
        val decoded = Json.decodeFromString(
            ExecuteJobNode.serializer(),
            """{"id":"job","name":"Gate","description":"d","jobName":"notify","awaitCompletion":true,"awaitTimeoutSeconds":42}""",
        )
        assertEquals("job", decoded.id)
        assertEquals("Gate", decoded.name)
        assertEquals("notify", decoded.jobName)
        assertTrue(decoded.awaitCompletion)
        assertEquals(42, decoded.awaitTimeoutSeconds)
    }
}
