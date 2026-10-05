@file:OptIn(Internal::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTest

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage of [SendSlackNode]: the webhook-URL resolution (named secret → [PipelineSecretService];
 * legacy inline fallback; neither = error), the `message` selection `when` (text set; text-null +
 * JsonPrimitive input; text-null + non-primitive JsonElement input; text-null + no input), dry-run
 * tracing (records the secret NAME, never resolves it) vs live POST, and 2xx success vs non-2xx
 * failure. Live HTTP is driven through [MockWebServer]; secret resolution through a real fake
 * [PipelineSecretService] registered in the DI [ProviderRegistry] (so the default `resolveForExecution`
 * runs for real).
 */
class SendSlackNodeTest {

    @Serializable
    private data class Note(val body: String)

    /** A real [PipelineSecretService] over an in-memory map, so the interface's default
     *  `resolveForExecution` (which calls [resolve]) is exercised end to end. */
    private class FakeSecrets(private val values: Map<String, String>) : PipelineSecretService {
        override suspend fun setSecret(name: String, value: String): PipelineSecret = error("unused")
        override suspend fun listSecrets(): List<PipelineSecret> = emptyList()
        override suspend fun deleteSecret(name: String) {}
        override suspend fun resolve(name: String): String? = values[name]
    }

    private val server = MockWebServer()

    init {
        server.start()
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        server.close()
        ProviderRegistry.clear()
    }

    private val webhookUrl: String get() = server.url("/services/T000/B000/XXX").toString()

    /** Register a secret store that resolves [SECRET_NAME] to the mock server URL. */
    private fun registerSecret() {
        provides<PipelineSecretService> { FakeSecrets(mapOf(SECRET_NAME to webhookUrl)) }
    }

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private fun stringInput(value: String) =
        NodeInputs(mapOf("in" to PipelineValue.of(value, String.serializer())))

    // ---- webhook-URL resolution ----

    @Test
    fun `the webhook URL is resolved from the named secret`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = "via secret")
        node.executeForTest(context, NodeInputs(emptyMap()))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/services/T000/B000/XXX", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("via secret", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the legacy inline webhook URL is used as a fallback without a secret`() = runTest {
        // No secret registered; the node posts to the inline URL. Proves back-compat with pre-secret graphs.
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookUrl = webhookUrl, text = "legacy")
        node.executeForTest(context, NodeInputs(emptyMap()))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("legacy", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `with neither a secret nor a URL the node fails`() = runTest {
        val node = SendSlackNode(id = "s", name = "Notify", text = "x")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("webhook secret" in (failure.message ?: ""), "expected a config hint, got: ${failure.message}")
        assertEquals(0, server.requestCount, "no request without a destination")
    }

    @Test
    fun `a blank secret name falls back to the legacy URL`() = runTest {
        // webhookSecret is blank -> the takeIf filters it out and the inline URL wins (no DI needed).
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = "  ", webhookUrl = webhookUrl, text = "blank secret")
        node.executeForTest(context, NodeInputs(emptyMap()))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("blank secret", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `blank destination settings fail using the node id fallback`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            SendSlackNode(id = "slack-node", webhookSecret = " ", webhookUrl = "", text = "x")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("slack-node" in failure.message.orEmpty())
    }

    // ---- message selection: text set (used as-is, input ignored) ----

    @Test
    fun `configured text is used as-is and the inbound value is ignored`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = "hello team")
        // Provide an input too, to prove text wins over it.
        val result = node.executeForTest(context, stringInput("ignored input"))
        assertNull(result, "a side-effect action node returns no output")

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("hello team", body["text"]?.jsonPrimitive?.content)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    // ---- message selection: text null + input is a JsonPrimitive (uses .content, no quotes) ----

    @Test
    fun `text-null with a primitive input uses the raw string content without JSON quoting`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = null)
        // A String PipelineValue encodes to a JsonPrimitive; .content strips the surrounding quotes.
        node.executeForTest(context, stringInput("just a plain line"))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("just a plain line", body["text"]?.jsonPrimitive?.content)
    }

    // ---- message selection: text null + input is a non-primitive JsonElement (uses toString()) ----

    @Test
    fun `text-null with an object input renders the element via toString`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = null)
        val inputs = NodeInputs(mapOf("in" to PipelineValue.of(Note("hi"), Note.serializer())))
        node.executeForTest(context, inputs)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        // A Note encodes to a JsonObject, whose toString() is its JSON text — that string is the message.
        val expected = Json.encodeToJsonElement(Note.serializer(), Note("hi")).toString()
        assertEquals(expected, body["text"]?.jsonPrimitive?.content)
        assertTrue("\"body\"" in (body["text"]?.jsonPrimitive?.content ?: ""), "the rendered text is the JSON object")
    }

    // ---- message selection: text null + no input (empty string) ----

    @Test
    fun `text-null with no input posts an empty message`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = null)
        node.executeForTest(context, NodeInputs(emptyMap()))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("", body["text"]?.jsonPrimitive?.content)
    }

    // ---- dry run: traces the secret name (never resolves it), makes no request, resolves no service ----

    @Test
    fun `dry run records the secret name into the trace and makes no HTTP request`() = runTest {
        // No PipelineSecretService registered: if the dry run resolved it, provide<> would throw.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendSlackNode(id = "slackNode", webhookSecret = SECRET_NAME, text = "dry message")
        val result = node.executeForTest(dry, stringInput("ignored"))
        assertNull(result)

        val recorded = trace.actions["slackNode"]
        assertTrue(recorded is JsonObject, "the action should be recorded under the node id")
        val obj = recorded.jsonObject
        assertEquals("sendSlack", obj["action"]?.jsonPrimitive?.content)
        assertEquals(SECRET_NAME, obj["webhookSecret"]?.jsonPrimitive?.content)
        assertEquals("dry message", obj["text"]?.jsonPrimitive?.content)
        assertEquals(0, server.requestCount, "dry runs must not POST to the webhook")
    }

    // ---- dry run with a null trace: the safe-call short-circuits, still no request ----

    @Test
    fun `dry run with a null trace records nothing and still makes no HTTP request`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = null)
        val node = SendSlackNode(id = "slackNode", webhookSecret = SECRET_NAME, text = "no trace")
        val result = node.executeForTest(dry, NodeInputs(emptyMap()))
        assertNull(result)
        assertEquals(0, server.requestCount, "dry runs must not POST even without a trace")
    }

    // ---- dry run with a primitive input: trace records the rendered content (covers the when in dry mode) ----

    @Test
    fun `dry run with a primitive input traces the rendered inbound content`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendSlackNode(id = "n", webhookSecret = SECRET_NAME, text = null)
        node.executeForTest(dry, stringInput("from input"))

        val obj = (trace.actions["n"] as JsonObject)
        assertEquals("from input", obj["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dry run without a secret omits the webhookSecret key`() = runTest {
        // A legacy-URL node in a dry run: no secret name to record, so the key is absent.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendSlackNode(id = "n", webhookUrl = webhookUrl, text = "legacy")
        node.executeForTest(dry, NodeInputs(emptyMap()))

        val obj = trace.actions["n"] as JsonObject
        assertTrue("webhookSecret" !in obj, "no secret configured -> no webhookSecret key")
        assertEquals("legacy", obj["text"]?.jsonPrimitive?.content)
    }

    // ---- live success: a 2xx response passes (check() is true) ----

    @Test
    fun `a 2xx response succeeds`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(204).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = "ok")
        val result = node.executeForTest(context, NodeInputs(emptyMap()))
        assertNull(result)
        assertEquals(1, server.requestCount)
    }

    // ---- live failure: a non-2xx response fails the node WITHOUT leaking the resolved URL ----

    @Test
    fun `a non-2xx response fails the node by name and does not leak the URL`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(500).build())
        val node = SendSlackNode(id = "s", name = "Alerts", webhookSecret = SECRET_NAME, text = "boom")
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        val message = failure.message ?: ""
        assertTrue("returned HTTP 500" in message, "expected the HTTP code in the failure, got: $message")
        assertTrue("Alerts" in message, "expected the node name in the failure, got: $message")
        assertTrue(webhookUrl !in message, "the resolved webhook URL must never appear in the failure")
    }

    @Test
    fun `a non-2xx response uses the node id when its name is blank`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(500).build())
        val failure = assertFailsWith<IllegalStateException> {
            SendSlackNode(id = "slack-node", webhookSecret = SECRET_NAME, text = "boom")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("slack-node" in failure.message.orEmpty())
    }

    // ---- construction defaults ----

    @Test
    fun `node construction defaults are applied`() {
        val node = SendSlackNode(id = "only-required")
        assertEquals("only-required", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertNull(node.webhookSecret)
        assertNull(node.webhookUrl)
        assertNull(node.text)
    }

    @Test
    fun `the posted body is exactly a text object`() = runTest {
        registerSecret()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = "exact")
        node.executeForTest(context, NodeInputs(emptyMap()))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val expected = buildJsonObject { put("text", "exact") }
        assertEquals(expected, sent)
    }

    // ---- synthetic default-ctor mask arms: PARTIAL subsets of the optional params ----

    @Test
    fun `constructing with id webhookSecret and text leaves name description and position default`() {
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, text = "hi")
        assertEquals("hi", node.text)
        assertEquals("", node.name)
        assertEquals("", node.description)
    }

    @Test
    fun `constructing with id webhookSecret and name leaves description text and position default`() {
        val node = SendSlackNode(id = "s", webhookSecret = SECRET_NAME, name = "Notify")
        assertEquals("Notify", node.name)
        assertNull(node.text, "text defaults to null when not provided")
        assertEquals("", node.description)
    }

    @Test
    fun `dry run with a set name records the action under the node id`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendSlackNode(id = "slackNode", name = "Notify", webhookSecret = SECRET_NAME, text = "named dry")
        node.executeForTest(dry, NodeInputs(emptyMap()))
        val obj = trace.actions["slackNode"] as JsonObject
        assertEquals("sendSlack", obj["action"]?.jsonPrimitive?.content)
        assertEquals("named dry", obj["text"]?.jsonPrimitive?.content)
        assertEquals(0, server.requestCount)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() {
        // id is the only no-default field; an empty object omits it, driving the generated
        // deserializer's `(seen & required) != required -> throwMissingFieldException` arm.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SendSlackNode.serializer(), "{}")
        }
    }

    private companion object {
        const val SECRET_NAME = "slack-webhook"
    }
}
