package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.DryRunTrace
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ThrowNode]: reaching it fails the run with its message (e.g. off a Wait for Build failure port). */
class ThrowNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    @Test
    fun `fails the run with the configured message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ThrowNode(id = "t", message = "Build failed").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("Build failed" in (e.message ?: ""), e.message)
    }

    @Test
    fun `appends the inbound value to the message for context`() = runTest {
        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("run-123"))))
        val e = assertFailsWith<IllegalStateException> {
            ThrowNode(id = "t", message = "boom").run(context, inputs)
        }
        assertTrue("boom" in (e.message ?: ""), e.message)
        assertTrue("run-123" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a blank message falls back to a default`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ThrowNode(id = "t", message = "").run(context, NodeInputs(emptyMap()))
        }
        assertTrue("Pipeline failed" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a dry run does not throw — it traces the intent`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val out = ThrowNode(id = "t", message = "").run(dry, NodeInputs(emptyMap()))
        assertTrue(out is NodeResult.Output, "a dry run walks the graph instead of aborting")
        val action = trace.actions["t"] as JsonObject
        assertEquals(JsonPrimitive("throw"), action["action"])
        assertEquals(JsonPrimitive("Pipeline failed"), action["message"])
    }

    @Test
    fun `a single string error property is rendered without raw json syntax`() = runTest {
        val inputs = NodeInputs(
            mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("error", "build unavailable") })),
        )

        val failure = assertFailsWith<IllegalStateException> {
            ThrowNode("t", message = "Deploy failed").run(context, inputs)
        }

        assertEquals("Deploy failed: build unavailable", failure.message)
    }

    @Test
    fun `non-string or multi-field objects retain their json representation`() = runTest {
        val numericError = NodeInputs(
            mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("error", 503) })),
        )
        val multiField = NodeInputs(
            mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("error", "bad"); put("retry", true) })),
        )

        assertTrue(
            assertFailsWith<IllegalStateException> { ThrowNode("t").run(context, numericError) }
                .message!!.contains("503"),
        )
        assertTrue(
            assertFailsWith<IllegalStateException> { ThrowNode("t").run(context, multiField) }
                .message!!.contains("retry"),
        )

        val oneUnrelatedField = NodeInputs(
            mapOf("in" to PipelineValue.ofJson(buildJsonObject { put("detail", "bad") })),
        )
        assertTrue(
            assertFailsWith<IllegalStateException> { ThrowNode("t").run(context, oneUnrelatedField) }
                .message!!.contains("detail"),
        )
    }

    @Test
    fun `json null is treated as no contextual cause`() = runTest {
        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonNull)))
        assertEquals(
            "Pipeline failed",
            assertFailsWith<IllegalStateException> { ThrowNode("t").run(context, inputs) }.message,
        )
    }

    @Test
    fun `dry run with a configured message and no trace is a no-op`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        val result = ThrowNode("t", message = "Stop").run(dry, NodeInputs(emptyMap()))
        assertTrue(result is NodeResult.Output)
    }

    @Test
    fun `dry run traces a configured nonblank message unchanged`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)

        ThrowNode("t", message = "Stop now").run(dry, NodeInputs(emptyMap()))

        assertEquals(JsonPrimitive("Stop now"), (trace.actions.getValue("t") as JsonObject)["message"])
    }
}
