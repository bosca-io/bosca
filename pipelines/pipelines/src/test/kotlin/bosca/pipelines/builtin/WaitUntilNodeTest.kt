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
import bosca.pipelines.node.NodePosition
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaitUntilNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private val input = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
    private val inputs get() = NodeInputs(mapOf("in" to input))

    private fun future(seconds: Long) = OffsetDateTime.now().plusSeconds(seconds).toString()
    private fun past(seconds: Long) = OffsetDateTime.now().minusSeconds(seconds).toString()

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `a durable run with a future static target suspends`() = runTest {
        val node = WaitUntilNode(id = "wait", until = future(3600))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Suspend, "a future target should park the run")
    }

    @Test
    fun `a durable run with a past target passes through without parking`() = runTest {
        val node = WaitUntilNode(id = "wait", until = past(3600))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Output, "an already-due target must not park")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a target resolved from an inbound field suspends`() = runTest {
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        val inbound = PipelineValue.ofJson(buildJsonObject {
            putJsonObject("order") { put("shipBy", future(7200)) }
        })
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to inbound)))
        assertTrue(result is NodeResult.Suspend, "a field-resolved future target should park the run")
    }

    @Test
    fun `a bare inbound timestamp string is used as the target`() = runTest {
        val node = WaitUntilNode(id = "wait") // no setting → use the inbound value itself
        val inbound = PipelineValue.ofJson(JsonPrimitive(future(120)))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to inbound)))
        assertTrue(result is NodeResult.Suspend, "a bare inbound timestamp should be the target")
    }

    @Test
    fun `a durable run with no resolvable target fails the run rather than parking forever`() = runTest {
        val node = WaitUntilNode(id = "wait") // no setting, and the inbound is not a timestamp string
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, inputs) }
        assertTrue("no target timestamp" in (failure.message ?: ""), "expected the missing-target error")
    }

    @Test
    fun `an unparseable target fails with a clear error`() = runTest {
        val node = WaitUntilNode(id = "wait", until = "next tuesday")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, inputs) }
        assertTrue("cannot parse target timestamp" in (failure.message ?: ""), "expected a parse error")
    }

    @Test
    fun `a non-durable run passes the value through without waiting`() = runTest {
        val node = WaitUntilNode(id = "wait", until = future(3600))
        val result = node.run(context, inputs) // no runId → nothing to resume
        assertTrue(result is NodeResult.Output, "a non-durable wait cannot park")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a dry run never suspends, passes the value through, and traces the would-be wait`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace, runId = UUID.random())
        val node = WaitUntilNode(id = "wait", until = future(3600))
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs must not park")
        assertEquals(input.value, result.value?.value)
        assertTrue("wait" in trace.actions, "the would-be wait should be traced")
    }

    @Test
    fun `the suspend thunk stages the inbound value and schedules a resume for the target`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        provides<Json> { Json } // REQUIRED: prepare builds the Job via provide<Json>()
        val captured = slot<Job>()
        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.enqueue(capture(captured)) } returns UUID.random()
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue) { queue }

        val runId = UUID.random()
        val node = WaitUntilNode(id = "wait", until = future(120))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)

        val suspend = node.run(durable, inputs) as NodeResult.Suspend
        val before = System.currentTimeMillis()
        suspend.enqueue()
        val after = System.currentTimeMillis()

        assertEquals(1, store.puts.size)
        assertEquals(runId to "wait", store.puts.single().first to store.puts.single().second)
        assertEquals(input.encode(Json), store.puts.single().third)

        // A PipelineDelayJob child is enqueued to wake ~target-now (~120s, allowing for the second
        // boundary), carrying the run/node correlation in its context for the run job's drive listener.
        val child = captured.captured
        val delay = Json.decodeFromJsonElement(PipelineDelayJob.serializer(), child.getDefinition())
        assertTrue(delay.wakeAtEpochMillis in (before + 117_000)..(after + 121_000), "wakes ~120s out, got ${delay.wakeAtEpochMillis - before}ms")
        val correlation = Json.decodeFromJsonElement(PipelineResumeCorrelation.serializer(), child.getContext())
        assertEquals(runId, correlation.runId)
        assertEquals("wait", correlation.nodeId)
    }

    @Test
    fun `untilField on a durable run with no inbound value fails with the missing-target error`() = runTest {
        // untilField branch: inbound?.encode ?: return null  → no target → error path
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, NodeInputs(emptyMap())) }
        assertTrue("no target timestamp" in (failure.message ?: ""), "expected the missing-target error")
    }

    @Test
    fun `untilField pointing at a missing field fails the run`() = runTest {
        // navigate walks but a segment is missing → null → no target → error path
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        val inbound = PipelineValue.ofJson(buildJsonObject {
            putJsonObject("order") { put("packedAt", future(600)) } // shipBy absent
        })
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to inbound)))
        }
        assertTrue("no target timestamp" in (failure.message ?: ""), "a missing field is no target")
    }

    @Test
    fun `untilField whose first segment is not an object fails the run`() = runTest {
        // navigate: first segment of the dot-path is not a JsonObject → return null early
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        val inbound = PipelineValue.ofJson(JsonPrimitive("not-an-object"))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to inbound)))
        }
        assertTrue("no target timestamp" in (failure.message ?: ""), "a non-object root resolves no target")
    }

    @Test
    fun `untilField resolving to a non-primitive (object) value fails the run`() = runTest {
        // navigate returns a JsonObject → `as? JsonPrimitive` is null → no target → error path
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        val inbound = PipelineValue.ofJson(buildJsonObject {
            putJsonObject("order") { putJsonObject("shipBy") { put("nested", "x") } }
        })
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to inbound)))
        }
        assertTrue("no target timestamp" in (failure.message ?: ""), "an object target is no ISO-8601 string")
    }

    @Test
    fun `a single-segment untilField resolves the target and suspends`() = runTest {
        // navigate loop with exactly one iteration (no dots in the path)
        val node = WaitUntilNode(id = "wait", untilField = "shipBy")
        val inbound = PipelineValue.ofJson(buildJsonObject { put("shipBy", future(900)) })
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to inbound)))
        assertTrue(result is NodeResult.Suspend, "a single-segment field target should park the run")
    }

    @Test
    fun `untilField takes precedence over a set until`() = runTest {
        // untilField.isNotBlank() short-circuits before until is even read
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy", until = past(60))
        val inbound = PipelineValue.ofJson(buildJsonObject {
            putJsonObject("order") { put("shipBy", future(7200)) }
        })
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to inbound)))
        assertTrue(result is NodeResult.Suspend, "untilField wins over the static until")
    }

    @Test
    fun `a durable run with a missing inbound value and no settings fails the run`() = runTest {
        // bare-inbound branch: inbound is null → returns null → missing-target error
        val node = WaitUntilNode(id = "wait")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, NodeInputs(emptyMap())) }
        assertTrue("no target timestamp" in (failure.message ?: ""), "no inbound and no settings is no target")
    }

    @Test
    fun `a bare inbound past timestamp passes through without parking`() = runTest {
        // bare-inbound branch resolves, but delaySeconds <= 0 → execute() pass-through
        val node = WaitUntilNode(id = "wait")
        val inbound = PipelineValue.ofJson(JsonPrimitive(past(120)))
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to inbound)))
        assertTrue(result is NodeResult.Output, "an already-due bare timestamp must not park")
        assertEquals(inbound.value, result.value?.value)
    }

    @Test
    fun `the missing-target error names the node by its name when set`() = runTest {
        // name.ifBlank { id }: name is non-blank → the name appears in the message
        val node = WaitUntilNode(id = "wait-id", name = "Ship Gate")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, inputs) }
        assertTrue("Ship Gate" in (failure.message ?: ""), "the node name should label the error")
        assertTrue("wait-id" !in (failure.message ?: ""), "the id should not be used when a name is set")
    }

    @Test
    fun `the missing-target error names the node by its id when name is blank`() = runTest {
        // name.ifBlank { id }: name is blank → the id appears in the message
        val node = WaitUntilNode(id = "wait-id")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, inputs) }
        assertTrue("wait-id" in (failure.message ?: ""), "the id should label the error when name is blank")
    }

    @Test
    fun `the parse error names the node by its name when set`() = runTest {
        // resolveTarget's parse-failure branch also threads name.ifBlank { id }
        val node = WaitUntilNode(id = "wait-id", name = "Ship Gate", until = "not-a-time")
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> { node.run(durable, inputs) }
        assertTrue("cannot parse target timestamp" in (failure.message ?: ""), "expected a parse error")
        assertTrue("Ship Gate" in (failure.message ?: ""), "the name should label the parse error")
        assertTrue("not-a-time" in (failure.message ?: ""), "the offending value should be in the message")
    }

    @Test
    fun `a dry run with no trace passes through and records nothing`() = runTest {
        // execute(): dryRun true but context.trace == null → the ?. safe-call short-circuits
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random())
        val node = WaitUntilNode(id = "wait", until = future(3600))
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs must not park")
        assertEquals(input.value, result.value?.value)
    }

    @Test
    fun `a dry run never even resolves an unparseable target`() = runTest {
        // run() short-circuits on dryRun BEFORE resolveTarget, so a bad until is harmless in dry mode
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace, runId = UUID.random())
        val node = WaitUntilNode(id = "wait", until = "garbage")
        val result = node.run(dry, inputs)
        assertTrue(result is NodeResult.Output, "dry runs never parse or fail on the target")
        assertEquals(input.value, result.value?.value)
        assertTrue("wait" in trace.actions, "the would-be wait is still traced")
    }

    @Test
    fun `the dry-run trace records the configured until and untilField`() = runTest {
        // execute() trace branch puts both action settings into the trace entry
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace, runId = UUID.random())
        val until = future(3600)
        val node = WaitUntilNode(id = "wait", until = until, untilField = "order.shipBy")
        node.run(dry, inputs)
        val entry = trace.actions["wait"] as JsonObject
        assertEquals("waitUntil", (entry["action"] as JsonPrimitive).content)
        assertEquals(until, (entry["until"] as JsonPrimitive).content)
        assertEquals("order.shipBy", (entry["untilField"] as JsonPrimitive).content)
    }

    @Test
    fun `execute called directly outside a dry run passes the value through untraced`() = runTest {
        // execute() with dryRun false: the trace block is skipped entirely
        val node = WaitUntilNode(id = "wait", until = future(3600))
        val out = node.executeForTest(context, inputs)
        assertEquals(input.value, out?.value)
    }

    @Test
    fun `execute on a dry run with an empty input passes a null value through`() = runTest {
        // execute() returns inputs.first; with no inbound that is null
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = WaitUntilNode(id = "wait")
        val out = node.executeForTest(dry, NodeInputs(emptyMap()))
        assertEquals(null, out)
        assertTrue("wait" in trace.actions, "a dry execute still traces even with no inbound")
    }

    @Test
    fun `a target exactly at the current second is treated as already due`() = runTest {
        // delaySeconds == 0 boundary: <= 0 is true → pass-through, not park
        val node = WaitUntilNode(id = "wait", until = OffsetDateTime.now().toString())
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, inputs)
        assertTrue(result is NodeResult.Output, "now (delay <= 0) must pass through, not park")
        assertEquals(input.value, result.value?.value)
    }

    private class CapturingResultStore : PipelineRunResultStore {
        val puts = mutableListOf<Triple<UUID, String, JsonElement>>()
        override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
            puts += Triple(runId, nodeId, result)
        }
        override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? = null
        override suspend fun remove(runId: UUID, nodeId: String) {}
    }

    // ----- synthetic default-args constructor: distinct PARTIAL subsets of the optional args -----
    // Each construction leaves a different combination of optional parameters at default, exercising
    // the <init>$default mask branches and reading the resulting defaulted getters.

    @Test
    fun `constructs with only id and reads every defaulted field`() = runTest {
        val node = WaitUntilNode(id = "wait")
        assertEquals("wait", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals("", node.untilField)
        assertEquals("", node.until)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with id plus name leaving the rest default`() = runTest {
        val node = WaitUntilNode(id = "wait", name = "Ship Gate")
        assertEquals("Ship Gate", node.name)
        assertEquals("", node.description)
        assertEquals("", node.untilField)
        assertEquals("", node.until)
    }

    @Test
    fun `constructs with id plus description leaving the rest default`() = runTest {
        val node = WaitUntilNode(id = "wait", description = "pauses until a time")
        assertEquals("pauses until a time", node.description)
        assertEquals("", node.name)
        assertEquals("", node.until)
    }

    @Test
    fun `constructs with id plus untilField leaving the rest default`() = runTest {
        val node = WaitUntilNode(id = "wait", untilField = "order.shipBy")
        assertEquals("order.shipBy", node.untilField)
        assertEquals("", node.until)
        assertEquals("", node.name)
    }

    @Test
    fun `constructs with id plus until leaving the rest default`() = runTest {
        val node = WaitUntilNode(id = "wait", until = "2026-07-01T09:00:00Z")
        assertEquals("2026-07-01T09:00:00Z", node.until)
        assertEquals("", node.untilField)
        assertEquals("", node.name)
    }

    @Test
    fun `constructs with an explicit position leaving the rest default`() = runTest {
        val node = WaitUntilNode(id = "wait", position = NodePosition(x = 7.0, y = 8.0))
        assertEquals(NodePosition(x = 7.0, y = 8.0), node.position)
        assertEquals(7.0, node.position.x)
        assertEquals(8.0, node.position.y)
        assertEquals("", node.until)
        assertEquals("", node.untilField)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() = runTest {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm — the arm a
        // valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(WaitUntilNode.serializer(), "{}")
        }
    }
}
