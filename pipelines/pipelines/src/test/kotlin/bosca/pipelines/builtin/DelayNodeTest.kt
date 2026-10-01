@file:OptIn(Internal::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTest

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.trigger.PipelineDelayJob
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DelayNodeTest {

    @Serializable
    private data class Person(val name: String)

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private val input = PipelineValue.of(Person("Ada"), Person.serializer())
    private val inputs get() = NodeInputs(mapOf("in" to input))

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `a durable run with a positive delay suspends instead of blocking`() = runTest {
        val node = DelayNode(id = "wait", delaySeconds = 30)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Suspend, "a durable delay should park the run")
    }

    @Test
    fun `a non-durable run passes the value through without waiting`() = runTest {
        val node = DelayNode(id = "wait", delaySeconds = 30)
        val result = node.run(context, inputs) // no runId → nothing to resume
        assertTrue(result is NodeResult.Output, "a non-durable delay cannot park")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a zero delay is an immediate pass-through even when durable`() = runTest {
        val node = DelayNode(id = "wait", delaySeconds = 0)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Output, "no point parking for zero time")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a dry run never suspends, passes the value through, and traces the would-be delay`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace, runId = UUID.random())
        val node = DelayNode(id = "wait", delaySeconds = 30)
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs must not park")
        assertEquals(input.value, result.value?.value)
        assertTrue("wait" in trace.actions, "the would-be delay should be traced")
    }

    @Test
    fun `a negative delay is an immediate pass-through even when durable`() = runTest {
        val node = DelayNode(id = "wait", delaySeconds = -5)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Output, "a non-positive delay never parks")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a dry run without a trace still passes the value through`() = runTest {
        // dryRun is true but trace is null → the trace?.actions safe-call no-ops, no NPE.
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random())
        val node = DelayNode(id = "wait", delaySeconds = 30)
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs must not park")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `execute traces the would-be delay payload under dry run`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = DelayNode(id = "wait", delaySeconds = 99)
        node.executeForTest(dry, inputs)
        val recorded = trace.actions["wait"] as JsonObject
        assertEquals("delay", recorded.getValue("action").jsonPrimitive.content)
        assertEquals(99L, recorded.getValue("delaySeconds").jsonPrimitive.long)
    }

    @Test
    fun `a non-durable run with empty inputs passes a null value through`() = runTest {
        val node = DelayNode(id = "wait", delaySeconds = 30)
        val result = node.run(context, NodeInputs(emptyMap()))
        assertTrue(result is NodeResult.Output, "no runId → cannot park")
        assertNull(result.value, "an empty input produces a null pass-through")
    }

    @Test
    fun `a durable run with empty inputs suspends and schedules a resume without staging any value`() = runTest {
        // Exercises durableTimerSuspend's inbound == null arm: the thunk skips the result store put
        // (nothing to stage) but still enqueues the delayed resume.
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        provides<Json> { Json } // REQUIRED: prepare builds the Job via provide<Json>()
        val captured = slot<Job>()
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }

        val runId = UUID.random()
        val node = DelayNode(id = "wait", delaySeconds = 12)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)

        val suspend = node.run(durable, NodeInputs(emptyMap())) as NodeResult.Suspend
        val before = System.currentTimeMillis()
        suspend.enqueue()
        val after = System.currentTimeMillis()

        // Always-stage: a null inbound stages JSON null (so the resume always finds the staged output).
        assertEquals(1, store.puts.size)
        assertEquals(kotlinx.serialization.json.JsonNull, store.puts.single().third)

        // A PipelineDelayJob child is enqueued to wake in ~12s, carrying the run/node correlation in its
        // context (the run job's drive listener resumes the node when the delay child completes).
        val child = captured.captured
        val delay = Json.decodeFromJsonElement(PipelineDelayJob.serializer(), child.getDefinition())
        assertTrue(delay.wakeAtEpochMillis in (before + 12_000)..(after + 12_000), "wakes ~12s out")
        val correlation = Json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), child.getContext())
        assertEquals(runId, correlation.runId)
        assertEquals("wait", correlation.nodeId)
    }

    @Test
    fun `the suspend thunk stages the inbound value and schedules a delayed self-resume`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        provides<Json> { Json } // REQUIRED: prepare builds the Job via provide<Json>()
        val captured = slot<Job>()
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }

        val runId = UUID.random()
        val node = DelayNode(id = "wait", delaySeconds = 45)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)

        val suspend = node.run(durable, inputs) as NodeResult.Suspend
        val before = System.currentTimeMillis()
        suspend.enqueue() // drive the deferred thunk
        val after = System.currentTimeMillis()

        // The inbound value is staged as this node's pass-through output.
        assertEquals(1, store.puts.size)
        assertEquals(runId to "wait", store.puts.single().first to store.puts.single().second)
        assertEquals(input.encode(Json), store.puts.single().third)

        // A PipelineDelayJob child is enqueued to wake in ~45s, carrying the run/node correlation in its
        // context — the run job's drive listener resumes the node when the delay child completes.
        val child = captured.captured
        val delay = Json.decodeFromJsonElement(PipelineDelayJob.serializer(), child.getDefinition())
        assertTrue(delay.wakeAtEpochMillis in (before + 45_000)..(after + 45_000), "wakes ~45s out")
        val correlation = Json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), child.getContext())
        assertEquals(runId, correlation.runId)
        assertEquals("wait", correlation.nodeId)
    }

    private class CapturingResultStore : PipelineRunResultStore {
        val puts = mutableListOf<Triple<UUID, String, JsonElement>>()
        override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
            puts += Triple(runId, nodeId, result)
        }
        override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? = null
        override suspend fun remove(runId: UUID, nodeId: String) {}
    }

    // --- synthetic default-ctor mask arms: PARTIAL subsets of the optional params ---

    @Test
    fun `constructing with id and delaySeconds leaves name description and position default`() = runTest {
        // delaySeconds set, name/description/position defaulted: flips one mask bit.
        val node = DelayNode(id = "wait", delaySeconds = 30)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(30L, node.delaySeconds)
        assertEquals(bosca.pipelines.node.NodePosition(), node.position)
    }

    @Test
    fun `constructing with id name and delaySeconds leaves description and position default`() = runTest {
        // A different partial subset: name + delaySeconds set, description/position defaulted.
        val node = DelayNode(id = "wait", name = "Pause", delaySeconds = 5)
        assertEquals("Pause", node.name)
        assertEquals("", node.description)
        assertEquals(5L, node.delaySeconds)
    }

    @Test
    fun `constructing with only an id leaves every optional including delaySeconds default`() = runTest {
        // All optionals defaulted -> delaySeconds defaults to 0 (a non-positive immediate pass-through).
        val node = DelayNode(id = "wait")
        assertEquals(0L, node.delaySeconds)
        assertEquals("", node.name)
        // a zero default never parks even when durable
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        assertTrue(node.run(durable, inputs) is NodeResult.Output)
    }

    @Test
    fun `execute traces the delay for a node that has a name set`() = runTest {
        // Drives the trace-set line (65) with a named node under dry run.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = DelayNode(id = "wait", name = "Pause", delaySeconds = 8)
        node.executeForTest(dry, inputs)
        val recorded = trace.actions["wait"] as JsonObject
        assertEquals("delay", recorded.getValue("action").jsonPrimitive.content)
        assertEquals(8L, recorded.getValue("delaySeconds").jsonPrimitive.long)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() = runTest {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(DelayNode.serializer(), "{}")
        }
    }

    @Test
    fun `durable timer thunk rejects a context without a run id`() = runTest {
        val suspended = durableTimerSuspend(context, "wait", input, 1.seconds)

        val failure = assertFailsWith<IllegalStateException> { suspended.enqueue() }

        assertTrue(failure.message.orEmpty().contains("run id"))
    }
}
