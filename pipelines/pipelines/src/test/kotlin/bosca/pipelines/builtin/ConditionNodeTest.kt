package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private fun tracingContext(trace: DryRunTrace) =
        PipelineContext(AuthenticationContext(null, null), Json, trace = trace)

    private fun inputs(value: JsonElement) = NodeInputs(mapOf("in" to PipelineValue.ofJson(value)))
    private fun tier(t: String) = buildJsonObject { put("tier", t) }

    @Test
    fun `a truthy expression routes the value to the true port`() = runTest {
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        val result = node.executeForTestValue(context, inputs(tier("gold")))
        assertEquals("true", result.port)
        assertEquals(tier("gold"), result.value, "the value passes through unchanged")
    }

    @Test
    fun `a falsy expression routes the value to the false port`() = runTest {
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        val result = node.executeForTestValue(context, inputs(tier("silver")))
        assertEquals("false", result.port)
        assertEquals(tier("silver"), result.value, "the value passes through unchanged")
    }

    @Test
    fun `a blank expression fails with the id when the name is blank`() = runTest {
        val node = ConditionNode(id = "cond-id", expression = "   ")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("no condition" in message, "message was: $message")
        assertTrue("'cond-id'" in message, "blank name falls back to id; message was: $message")
    }

    @Test
    fun `a blank expression fails with the name when the name is set`() = runTest {
        val node = ConditionNode(id = "cond-id", name = "Has Gold", expression = "")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("no condition" in message, "message was: $message")
        assertTrue("'Has Gold'" in message, "set name is used; message was: $message")
    }

    @Test
    fun `a missing input fails with the id`() = runTest {
        val node = ConditionNode(id = "cond-id", name = "Has Gold", expression = "tier = 'gold'")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        val message = failure.message ?: ""
        assertTrue("requires an input" in message, "message was: $message")
        // The input-missing path always uses the id, never the name.
        assertTrue("'cond-id'" in message, "message was: $message")
    }

    @Test
    fun `an invalid expression fails with the id when the name is blank`() = runTest {
        val node = ConditionNode(id = "cond-id", expression = "this is not >< valid jsonata (")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("invalid expression" in message, "message was: $message")
        assertTrue("'cond-id'" in message, "blank name falls back to id; message was: $message")
        assertTrue("this is not >< valid jsonata (" in message, "the expression is echoed; message was: $message")
    }

    @Test
    fun `an invalid expression fails with the name when the name is set`() = runTest {
        val node = ConditionNode(id = "cond-id", name = "Bad Cond", expression = "@#$%^ broken (")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("invalid expression" in message, "message was: $message")
        assertTrue("'Bad Cond'" in message, "set name is used; message was: $message")
    }

    @Test
    fun `the taken true branch is recorded on the trace when trace is present`() = runTest {
        val trace = DryRunTrace()
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        val result = node.executeForTestValue(tracingContext(trace), inputs(tier("gold")))
        assertEquals("true", result.port)
        val recorded = trace.actions["cond"]
        assertTrue(recorded is JsonObject, "an action JSON object was recorded")
        assertEquals("true", recorded.jsonObject["takenBranch"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the taken false branch is recorded on the trace when trace is present`() = runTest {
        val trace = DryRunTrace()
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        val result = node.executeForTestValue(tracingContext(trace), inputs(tier("silver")))
        assertEquals("false", result.port)
        assertEquals("false", trace.actions["cond"]?.jsonObject?.get("takenBranch")?.jsonPrimitive?.content)
    }

    @Test
    fun `no trace entry is recorded when trace is null`() = runTest {
        // context has trace == null; the safe-call short-circuits and nothing is recorded.
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        val result = node.executeForTestValue(context, inputs(tier("gold")))
        assertEquals("true", result.port)
        // Nothing to assert on a null trace beyond a successful, non-throwing run.
    }

    @Test
    fun `a trace present but with another node's action leaves this node absent until executed`() = runTest {
        // Exercises the trace != null arm where this node id is not yet keyed.
        val trace = DryRunTrace()
        assertNull(trace.actions["cond"])
        val node = ConditionNode(id = "cond", expression = "tier = 'gold'")
        node.executeForTestValue(tracingContext(trace), inputs(tier("gold")))
        assertEquals("true", trace.actions["cond"]?.jsonObject?.get("takenBranch")?.jsonPrimitive?.content)
    }

    // --- synthetic default-ctor mask arms: PARTIAL subsets of the optional params ---

    @Test
    fun `constructing with id expression and name leaves description and position default`() = runTest {
        // name set, description/position defaulted: flips one mask bit, not all-or-nothing.
        val node = ConditionNode(id = "cond", name = "Is Gold", expression = "tier = 'gold'")
        assertEquals("Is Gold", node.name)
        assertEquals("", node.description)
        assertEquals(bosca.pipelines.node.NodePosition(), node.position)
        // the constructed node still routes correctly
        assertEquals("true", node.executeForTestValue(context, inputs(tier("gold"))).port)
    }

    @Test
    fun `constructing with id expression and description leaves name and position default`() = runTest {
        // A different partial subset: description set, name/position defaulted.
        val node = ConditionNode(id = "cond", description = "gate", expression = "tier = 'gold'")
        assertEquals("gate", node.description)
        assertEquals("", node.name)
        assertEquals("false", node.executeForTestValue(context, inputs(tier("silver"))).port)
    }

    @Test
    fun `the false branch is recorded on the trace when the name is set`() = runTest {
        // Drives the trace-set line (55) on the success path with a named node.
        val trace = DryRunTrace()
        val node = ConditionNode(id = "cond", name = "Is Gold", expression = "tier = 'gold'")
        node.executeForTestValue(tracingContext(trace), inputs(tier("bronze")))
        assertEquals("false", trace.actions["cond"]?.jsonObject?.get("takenBranch")?.jsonPrimitive?.content)
    }

    // --- generated deserializer: the throwMissingFieldException arm (a required field absent) ---

    @Test
    fun `decoding JSON missing a required field throws`() = runTest {
        // id and expression are required (no defaults); an empty object omits them, driving the
        // generated deserializer's `(seen & required) != required -> throwMissingFieldException` arm —
        // the arm a valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ConditionNode.serializer(), "{}")
        }
    }
}
