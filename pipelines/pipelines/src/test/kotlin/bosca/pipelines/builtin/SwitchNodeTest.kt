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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private fun inputs(value: JsonElement) = NodeInputs(mapOf("in" to PipelineValue.ofJson(value)))
    private fun tier(t: String) = buildJsonObject { put("tier", t) }

    private val cases = listOf(
        SwitchCase(label = "vip", expression = "tier = 'gold'"),
        SwitchCase(label = "standard", expression = "tier = 'silver'"),
    )

    @Test
    fun `routes the value to the first matching case's port`() = runTest {
        val node = SwitchNode(id = "sw", cases = cases)
        val result = node.executeForTestValue(context, inputs(tier("silver")))
        assertEquals("standard", result.port)
        assertEquals(tier("silver"), result.value, "the value passes through unchanged")
    }

    @Test
    fun `the first truthy case wins even when a later case also matches`() = runTest {
        // Both cases match {"tier":"gold"}; the earlier one (vip) must win.
        val node = SwitchNode(
            id = "sw",
            cases = listOf(
                SwitchCase("vip", "tier = 'gold'"),
                SwitchCase("anyone", "tier != null"),
            ),
        )
        assertEquals("vip", node.executeForTestValue(context, inputs(tier("gold"))).port)
    }

    @Test
    fun `an unmatched value routes to the default port`() = runTest {
        val node = SwitchNode(id = "sw", cases = cases)
        assertEquals("default", node.executeForTestValue(context, inputs(tier("bronze"))).port)
    }

    @Test
    fun `the default port name is configurable`() = runTest {
        val node = SwitchNode(id = "sw", cases = cases, defaultLabel = "fallback")
        assertEquals("fallback", node.executeForTestValue(context, inputs(tier("bronze"))).port)
    }

    @Test
    fun `a switch with no cases fails fast`() = runTest {
        val node = SwitchNode(id = "sw", cases = emptyList())
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        assertTrue("no cases" in (failure.message ?: ""))
    }

    @Test
    fun `an invalid JSONata case expression fails with invalid expression`() = runTest {
        // A syntactically broken JSONata expression: Jsonata.jsonata(...) throws, the catch fires.
        // Mirrors the proven-throwing string used by ConditionNodeTest.
        val node = SwitchNode(
            id = "sw",
            cases = listOf(SwitchCase(label = "bad", expression = "this is not >< valid jsonata (")),
        )
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("invalid expression" in message, "message was: $message")
        // The offending case label and the raw expression are surfaced for debugging.
        assertTrue("bad" in message, "the failing case label is named; message was: $message")
        assertTrue("this is not >< valid jsonata (" in message, "the expression is echoed; message was: $message")
    }

    @Test
    fun `a case with a blank expression fails the check`() = runTest {
        val node = SwitchNode(
            id = "sw",
            cases = listOf(SwitchCase(label = "empty", expression = "")),
        )
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("no expression" in message, "message was: $message")
        assertTrue("empty" in message, "the failing case label is named: $message")
    }

    @Test
    fun `a whitespace-only expression is treated as blank`() = runTest {
        // isNotBlank() distinguishes "   " (blank) from "" — both arms reach the same check failure.
        val node = SwitchNode(
            id = "sw",
            cases = listOf(SwitchCase(label = "spaces", expression = "   ")),
        )
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        assertTrue("no expression" in (failure.message ?: ""), "message was: ${failure.message}")
    }

    @Test
    fun `a missing input fails with requires an input`() = runTest {
        // inputs.first is null -> the elvis error() arm fires.
        val node = SwitchNode(id = "sw", cases = cases)
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        assertTrue("requires an input" in (failure.message ?: ""), "message was: ${failure.message}")
    }

    @Test
    fun `the error message uses the node name when it is set`() = runTest {
        // name is non-blank -> name.ifBlank { id } yields the name (not the id) in the message.
        val node = SwitchNode(id = "the-id", name = "My Switch", cases = cases)
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        val message = failure.message ?: ""
        assertTrue("My Switch" in message, "message was: $message")
        assertTrue("the-id" !in message, "the id should be replaced by the name: $message")
    }

    @Test
    fun `the error message falls back to the id when the name is blank`() = runTest {
        // name defaults to "" (blank) -> name.ifBlank { id } yields the id.
        val node = SwitchNode(id = "the-id", cases = cases)
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, NodeInputs(emptyMap())) }
        assertTrue("the-id" in (failure.message ?: ""), "message was: ${failure.message}")
    }

    @Test
    fun `a trace records the taken case for a matched route`() = runTest {
        val trace = DryRunTrace()
        val traced = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = SwitchNode(id = "sw", cases = cases)

        val result = node.executeForTestValue(traced, inputs(tier("silver")))

        assertEquals("standard", result.port)
        assertEquals(
            buildJsonObject { put("takenCase", "standard") },
            trace.actions["sw"],
            "the trace records the matched case's port",
        )
    }

    @Test
    fun `a trace records the default port when nothing matches`() = runTest {
        val trace = DryRunTrace()
        val traced = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = SwitchNode(id = "sw", cases = cases, defaultLabel = "fallback")

        node.executeForTestValue(traced, inputs(tier("bronze")))

        assertEquals(
            buildJsonObject { put("takenCase", "fallback") },
            trace.actions["sw"],
        )
    }

    @Test
    fun `without a trace nothing is recorded`() = runTest {
        // trace is null -> the trace?.actions?.set safe-call short-circuits, no NPE, no record.
        val plain = PipelineContext(AuthenticationContext(null, null), Json)
        assertNull(plain.trace)
        val node = SwitchNode(id = "sw", cases = cases)
        // Must still route normally.
        assertEquals("standard", node.executeForTestValue(plain, inputs(tier("silver"))).port)
    }

    @Test
    fun `SwitchCase round-trips through JSON`() = runTest {
        val case = SwitchCase(label = "vip", expression = "tier = 'gold'")
        val encoded = Json.encodeToString(SwitchCase.serializer(), case)
        val decoded = Json.decodeFromString(SwitchCase.serializer(), encoded)
        assertEquals(case, decoded)
    }

    @Test
    fun `SwitchCase equals differs per field`() = runTest {
        val case = SwitchCase(label = "vip", expression = "tier = 'gold'")
        assertEquals(case, case.copy())
        assertTrue(case != case.copy(label = "other"))
        assertTrue(case != case.copy(expression = "tier = 'silver'"))
    }

    // --- synthetic default-ctor mask arms: partial subsets of optional params ---

    @Test
    fun `constructing with only id and cases leaves name description defaultLabel and position default`() = runTest {
        // Some optionals provided (cases), the rest (name/description/defaultLabel/position) defaulted.
        val node = SwitchNode(id = "sw", cases = cases)
        assertEquals("sw", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals("default", node.defaultLabel)
        assertEquals(cases, node.cases)
        // a different optional left default: position
        assertEquals(bosca.pipelines.node.NodePosition(), node.position)
    }

    @Test
    fun `constructing with id name and defaultLabel leaves description cases and position default`() = runTest {
        // A different partial subset: name + defaultLabel set, cases/description/position defaulted.
        val node = SwitchNode(id = "sw", name = "Router", defaultLabel = "elsewhere")
        assertEquals("Router", node.name)
        assertEquals("elsewhere", node.defaultLabel)
        assertEquals("", node.description)
        assertTrue(node.cases.isEmpty(), "cases default to empty")
    }

    @Test
    fun `constructing with id and a description only leaves the remaining optionals default`() = runTest {
        val node = SwitchNode(id = "sw", description = "routes by tier")
        assertEquals("routes by tier", node.description)
        assertEquals("", node.name)
        assertEquals("default", node.defaultLabel)
        assertTrue(node.cases.isEmpty())
    }

    @Test
    fun `the trace records the taken default port even when the name is set`() = runTest {
        // Name set -> name.ifBlank{id} keeps the name in any message; here a successful default route
        // still writes the takenCase action (line that sets context.trace).
        val trace = DryRunTrace()
        val traced = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = SwitchNode(id = "the-id", name = "My Switch", cases = cases)
        node.executeForTestValue(traced, inputs(tier("bronze")))
        assertEquals(
            buildJsonObject { put("takenCase", "default") },
            trace.actions["the-id"],
            "the action is keyed by node id even when a name is set",
        )
    }

    @Test
    fun `an invalid expression on a node with a name surfaces the name`() = runTest {
        // The catch arm (invalid expression) using name.ifBlank{id} -> name branch.
        val node = SwitchNode(
            id = "the-id",
            name = "Named Switch",
            cases = listOf(SwitchCase(label = "bad", expression = "this is not >< valid jsonata (")),
        )
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("invalid expression" in message, "message was: $message")
        assertTrue("Named Switch" in message, "the set name is surfaced; message was: $message")
        assertTrue("the-id" !in message, "the name replaces the id; message was: $message")
    }

    @Test
    fun `a blank-expression case on a node with a name surfaces the name`() = runTest {
        // The blank-expression check using name.ifBlank{id} -> name branch.
        val node = SwitchNode(
            id = "the-id",
            name = "Named Switch",
            cases = listOf(SwitchCase(label = "empty", expression = "")),
        )
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("no expression" in message, "message was: $message")
        assertTrue("Named Switch" in message, "the set name is surfaced; message was: $message")
    }

    @Test
    fun `a no-cases switch with a name surfaces the name`() = runTest {
        // The cases.isNotEmpty() check using name.ifBlank{id} -> name branch.
        val node = SwitchNode(id = "the-id", name = "Named Switch", cases = emptyList())
        val failure = assertFailsWith<IllegalStateException> { node.executeForTestValue(context, inputs(tier("gold"))) }
        val message = failure.message ?: ""
        assertTrue("no cases" in message, "message was: $message")
        assertTrue("Named Switch" in message, "the set name is surfaced; message was: $message")
    }

    // --- generated deserializer: the throwMissingFieldException arm (a required field absent) ---

    @Test
    fun `decoding a SwitchCase missing a required field throws`() = runTest {
        // SwitchCase has two required (no-default) fields, label and expression. Omitting one drives the
        // generated deserializer's `(seen & required) != required -> throwMissingFieldException` arm —
        // the arm a valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SwitchCase.serializer(), """{"label":"vip"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SwitchCase.serializer(), """{"expression":"tier = 'gold'"}""")
        }
    }

    @Test
    fun `decoding a SwitchNode missing the required id throws`() = runTest {
        // id is the only no-default field on a SwitchNode; an empty object omits it, driving the
        // generated deserializer's throwMissingFieldException arm.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SwitchNode.serializer(), "{}")
        }
    }
}
