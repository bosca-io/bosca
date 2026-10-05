@file:OptIn(InternalDI::class)

package bosca.pipelines.builtin

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.service.PipelineRunResultStore
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [WaitForInputNode]: parks a durable run until an external actor provides a value; nothing is enqueued. */
class WaitForInputNodeTest {

    private class CapturingResultStore : PipelineRunResultStore {
        val puts = mutableListOf<Triple<UUID, String, JsonElement>>()
        override suspend fun put(runId: UUID, nodeId: String, result: JsonElement, port: String?) {
            puts += Triple(runId, nodeId, result)
        }
        override suspend fun get(runId: UUID, nodeId: String): PipelineRunNodeResult? = null
        override suspend fun remove(runId: UUID, nodeId: String) {}
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun inputs(v: PipelineValue) = NodeInputs(mapOf("in" to v))
    private fun durable(runId: UUID = UUID.random()) = PipelineContext(AuthenticationContext(null, null), Json, runId = runId)

    @Test
    fun `a durable run parks awaiting external input`() = runTest {
        val result = WaitForInputNode(id = "wait").run(durable(), inputs(PipelineValue.ofJson(JsonPrimitive("x"))))
        assertTrue(result is NodeResult.Suspend)
    }

    @Test
    fun `the suspend thunk stages the inbound value as a placeholder and enqueues nothing`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }
        val runId = UUID.random()

        val suspend = WaitForInputNode(id = "wait").run(durable(runId), inputs(PipelineValue.ofJson(JsonPrimitive("v")))) as NodeResult.Suspend
        suspend.enqueue()

        // Only the placeholder is staged — there is no backing job; the run resumes via provideInput.
        assertEquals(1, store.puts.size)
        assertEquals(runId to "wait", store.puts.single().first to store.puts.single().second)
        assertEquals(JsonPrimitive("v"), store.puts.single().third)
    }

    @Test
    fun `the suspend thunk stages JsonNull when there is no inbound value`() = runTest {
        val store = CapturingResultStore()
        provides<PipelineRunResultStore> { store }

        val suspended = WaitForInputNode(id = "wait")
            .run(durable(), NodeInputs(emptyMap())) as NodeResult.Suspend
        suspended.enqueue()

        assertEquals(JsonNull, store.puts.single().third)
    }

    @Test
    fun `a non-durable run has nothing to park on and passes the inbound value through`() = runTest {
        val result = WaitForInputNode(id = "wait", name = "Wait").run(
            PipelineContext(AuthenticationContext(null, null), Json),
            inputs(PipelineValue.ofJson(JsonPrimitive("x"))),
        )
        assertTrue(result is NodeResult.Output)
        assertEquals(JsonPrimitive("x"), result.value?.value)
    }

    @Test
    fun `a dry run never suspends and passes the inbound value through`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(
            AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random(), trace = trace,
        )
        val result = WaitForInputNode(id = "wait", prompt = "Supply a value").run(
            dry,
            inputs(PipelineValue.ofJson(JsonPrimitive("v"))),
        )
        assertTrue(result is NodeResult.Output)
        assertEquals(JsonPrimitive("v"), result.value?.value)
        val action = trace.actions["wait"] as JsonObject
        assertEquals(JsonPrimitive("waitForInput"), action["action"])
        assertEquals(JsonPrimitive("Supply a value"), action["prompt"])
    }

    @Test
    fun `a dry run without a trace still passes through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random())
        val result = WaitForInputNode(id = "wait").run(dry, NodeInputs(emptyMap()))
        assertTrue(result is NodeResult.Output)
        assertEquals(null, result.value)
    }
}
