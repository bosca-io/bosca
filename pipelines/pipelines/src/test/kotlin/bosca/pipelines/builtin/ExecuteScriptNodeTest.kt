@file:OptIn(Internal::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTest

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.model.Script
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecuteScriptNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val scriptId = UUID.random()
    private val input = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
    private val inputs get() = NodeInputs(mapOf("in" to input))
    private val emptyInputs get() = NodeInputs(emptyMap())

    private fun context(
        dryRun: Boolean = false,
        trace: DryRunTrace? = null,
    ) = PipelineContext(AuthenticationContext(null, null), Json, dryRun = dryRun, trace = trace)

    /** A stored script the execution service runs. */
    private fun storedScript() = Script(key = "transform", name = "Transform", source = "input")

    /**
     * Register relaxed mocks for the two services [ExecuteScriptNode] resolves via `provide`.
     * Returns the pair so a test can stub/verify them.
     */
    private fun registerServices(): Pair<ScriptService, ScriptExecutionService> {
        val scriptService = mockk<ScriptService>(relaxed = true)
        val executionService = mockk<ScriptExecutionService>(relaxed = true)
        provides<ScriptService> { scriptService }
        provides<ScriptExecutionService> { executionService }
        return scriptService to executionService
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // ---- dry-run && !dryRunEnabled: skip, trace, return null ------------------------------------

    @Test
    fun `dry run without dryRunEnabled records the would-be invocation and outputs nothing`() = runTest {
        // services are registered but must never be touched on this path
        val (scriptService, executionService) = registerServices()
        val trace = DryRunTrace()
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId, dryRunEnabled = false)

        val result = node.executeForTest(context(dryRun = true, trace = trace), inputs)

        assertNull(result, "a skipped dry run produces no output")
        assertTrue("s1" in trace.actions, "the would-be invocation should be traced")
        val recorded = trace.actions.getValue("s1").jsonObject
        assertEquals("executeScript", recorded.getValue("action").let { (it as JsonPrimitive).content })
        assertEquals(scriptId.toString(), recorded.getValue("scriptId").let { (it as JsonPrimitive).content })
        assertEquals(input.encode(Json), recorded.getValue("input"))
        // verify the services were never invoked on the skip path
        coVerify(exactly = 0) { scriptService.get(any()) }
        coVerify(exactly = 0) { executionService.executeAsJson(any(), any()) }
    }

    @Test
    fun `dry run skip with no inbound value records JsonNull for input`() = runTest {
        registerServices()
        val trace = DryRunTrace()
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)

        val result = node.executeForTest(context(dryRun = true, trace = trace), emptyInputs)

        assertNull(result)
        val recorded = trace.actions.getValue("s1").jsonObject
        assertEquals(JsonNull, recorded.getValue("input"), "absent input is traced as JsonNull")
    }

    @Test
    fun `dry run skip with a null trace does not throw and still outputs nothing`() = runTest {
        // trace == null arm of `context.trace?.actions?.set(...)`
        registerServices()
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)

        val result = node.executeForTest(context(dryRun = true, trace = null), inputs)

        assertNull(result, "with no trace the node simply skips and returns null")
    }

    // ---- live run (not dry): resolve + execute -------------------------------------------------

    @Test
    fun `live run resolves the script and outputs the execution result as JSON`() = runTest {
        val (scriptService, executionService) = registerServices()
        val script = storedScript()
        val output = buildJsonObject { put("greeting", "hi Ada") }
        coEvery { scriptService.get(scriptId) } returns script
        coEvery { executionService.executeAsJson(script, any()) } returns output

        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)
        val result = node.executeForTest(context(), inputs)

        assertEquals(output, result?.value, "the node outputs the script's JSON result")
        coVerify { scriptService.get(scriptId) }
        coVerify { executionService.executeAsJson(eq(script), any()) }
    }

    @Test
    fun `live run hands the inbound value to the script context as its input`() = runTest {
        val (scriptService, executionService) = registerServices()
        val script = storedScript()
        coEvery { scriptService.get(scriptId) } returns script
        val ctxSlot = slot<DefaultScriptContext>()
        coEvery { executionService.executeAsJson(eq(script), capture(ctxSlot)) } returns JsonNull

        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)
        node.executeForTest(context(), inputs)

        assertEquals(
            input.encode(Json),
            ctxSlot.captured.input,
            "the inbound value (encoded) becomes the script's input",
        )
    }

    @Test
    fun `live run with no inbound value hands JsonNull to the script context`() = runTest {
        val (scriptService, executionService) = registerServices()
        val script = storedScript()
        coEvery { scriptService.get(scriptId) } returns script
        val ctxSlot = slot<DefaultScriptContext>()
        coEvery { executionService.executeAsJson(eq(script), capture(ctxSlot)) } returns JsonNull

        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)
        node.executeForTest(context(), emptyInputs)

        assertSame(JsonNull, ctxSlot.captured.input, "absent input arrives as JsonNull (elvis right arm)")
    }

    @Test
    fun `script not found fails the run`() = runTest {
        val (scriptService, _) = registerServices()
        coEvery { scriptService.get(scriptId) } returns null

        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)
        val failure = assertFailsWith<IllegalStateException> { node.executeForTest(context(), inputs) }

        assertTrue("script not found" in (failure.message ?: ""), "expected a not-found error, got: ${failure.message}")
        assertTrue("s1" in (failure.message ?: ""), "the node id should be in the error")
    }

    // ---- dry-run && dryRunEnabled: short-circuits the skip; actually runs ----------------------

    @Test
    fun `dry run with dryRunEnabled actually resolves and executes the script`() = runTest {
        // context.dryRun is true but dryRunEnabled flips !dryRunEnabled to false -> the && is false,
        // so the node runs for real and records nothing into the trace.
        val (scriptService, executionService) = registerServices()
        val script = storedScript()
        val output = buildJsonObject { put("ok", true) }
        coEvery { scriptService.get(scriptId) } returns script
        coEvery { executionService.executeAsJson(script, any()) } returns output

        val trace = DryRunTrace()
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId, dryRunEnabled = true)
        val result = node.executeForTest(context(dryRun = true, trace = trace), inputs)

        assertEquals(output, result?.value, "a dry-run-enabled script's real output flows downstream")
        assertTrue("s1" !in trace.actions, "a dry-run-enabled script does not record a would-be action")
        coVerify { scriptService.get(scriptId) }
        coVerify { executionService.executeAsJson(eq(script), any()) }
    }

    // ---- @Serializable model arms (the node itself is @Serializable) ---------------------------

    @Test
    fun `node round-trips through JSON and equals a decoded minimal form`() = runTest {
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId)
        val encoded = Json.encodeToString(ExecuteScriptNode.serializer(), node)
        val decoded = Json.decodeFromString(ExecuteScriptNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.scriptId, decoded.scriptId)
        assertEquals(node.dryRunEnabled, decoded.dryRunEnabled)

        // minimal JSON exercises the default-value deserializer arms (name/description/dryRunEnabled/position)
        val minimal = Json.decodeFromString(
            ExecuteScriptNode.serializer(),
            """{"id":"s1","scriptId":"$scriptId"}""",
        )
        assertEquals("s1", minimal.id)
        assertEquals(scriptId, minimal.scriptId)
        assertEquals("", minimal.name)
        assertEquals("", minimal.description)
        assertEquals(false, minimal.dryRunEnabled)
    }

    // ---- synthetic default-ctor mask arms: PARTIAL subsets of the optional params ----------------

    @Test
    fun `constructing with id scriptId and name leaves description dryRunEnabled and position default`() {
        // name set, the rest (description/dryRunEnabled/position) defaulted: flips one mask bit.
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId, name = "Runner")
        assertEquals("Runner", node.name)
        assertEquals("", node.description)
        assertEquals(false, node.dryRunEnabled)
    }

    @Test
    fun `constructing with id scriptId and dryRunEnabled leaves name description and position default`() {
        // A different partial subset: dryRunEnabled set, name/description/position defaulted.
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId, dryRunEnabled = true)
        assertEquals(true, node.dryRunEnabled)
        assertEquals("", node.name)
        assertEquals("", node.description)
    }

    @Test
    fun `constructing with id scriptId and description leaves the rest default`() {
        val node = ExecuteScriptNode(id = "s1", scriptId = scriptId, description = "runs a transform")
        assertEquals("runs a transform", node.description)
        assertEquals("", node.name)
        assertEquals(false, node.dryRunEnabled)
    }

    // ---- generated deserializer: the throwMissingFieldException arm (a required field absent) -----

    @Test
    fun `decoding JSON missing a required field throws`() {
        // id and scriptId are both required (no defaults); an empty object omits them, driving the
        // generated deserializer's `(seen & required) != required -> throwMissingFieldException` arm —
        // the arm a valid round-trip never reaches.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ExecuteScriptNode.serializer(), "{}")
        }
    }
}
