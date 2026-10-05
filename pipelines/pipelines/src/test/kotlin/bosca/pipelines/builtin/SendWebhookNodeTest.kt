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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage of [SendWebhookNode]: URL resolution (named secret → [PipelineSecretService]; legacy inline
 * fallback; neither = error), signing-key resolution (named secret; legacy inline key; none = no
 * signature), dry-run tracing (records the URL secret NAME + a `signed` flag, never resolving anything)
 * vs live POST, 2xx success vs non-2xx failure, and the @Serializable arms. Live HTTP is driven through
 * [MockWebServer]; secret resolution through a real fake [PipelineSecretService] in the DI registry.
 */
class SendWebhookNodeTest {

    @Serializable
    private data class Payload(val name: String, val count: Int)

    /** A real [PipelineSecretService] over an in-memory map so the default `resolveForExecution` runs. */
    private class FakeSecrets(private val values: Map<String, String>) : PipelineSecretService {
        override suspend fun setSecret(name: String, value: String): PipelineSecret = error("unused")
        override suspend fun listSecrets(): List<PipelineSecret> = emptyList()
        override suspend fun deleteSecret(name: String) {}
        override suspend fun resolve(name: String): String? = values[name]
    }

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    private val payload = Payload("Ada", 7)
    private val input = PipelineValue.of(payload, Payload.serializer())
    private val inputs get() = NodeInputs(mapOf("in" to input))
    private val emptyInputs get() = NodeInputs(emptyMap())

    private val server = MockWebServer().apply { start() }
    private val url get() = server.url("/").toString()

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        server.close()
        ProviderRegistry.clear()
    }

    /** Register a secret store resolving the URL secret to the mock server, and the signing secret to [signingKey]. */
    private fun registerSecrets(signingKey: String? = null) {
        val values = buildMap {
            put(URL_SECRET, url)
            if (signingKey != null) put(SIGN_SECRET, signingKey)
        }
        provides<PipelineSecretService> { FakeSecrets(values) }
    }

    /** Independent HMAC-SHA256 so the asserted header value is computed, not copied from the node. */
    private fun expectedSignature(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // URL resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the URL is resolved from the named secret`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        node.executeForTest(context, inputs)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(input.encode(Json).toString(), request.body!!.utf8())
    }

    @Test
    fun `the legacy inline URL is used as a fallback without a secret`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", url = url)

        node.executeForTest(context, inputs)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `blank secret settings fall back to nonblank legacy settings`() = runTest {
        val key = "inline-key"
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(
            id = "wh",
            urlSecret = "   ",
            signingSecret = " ",
            url = url,
            secret = key,
        )

        node.executeForTest(context, inputs)

        val request = server.takeRequest()
        assertEquals(expectedSignature(key, request.body!!.utf8()), request.headers["X-Bosca-Signature"])
    }

    @Test
    fun `blank URL settings fail using the node id fallback`() = runTest {
        val node = SendWebhookNode(id = "wh", urlSecret = " ", url = "")

        val failure = assertFailsWith<IllegalStateException> { node.executeForTest(context, inputs) }

        assertTrue("'wh'" in failure.message.orEmpty())
    }

    @Test
    fun `with neither a URL secret nor a URL the node fails`() = runTest {
        val node = SendWebhookNode(id = "wh", name = "Notify")
        val failure = assertFailsWith<IllegalStateException> { node.executeForTest(context, inputs) }
        assertTrue("URL secret" in (failure.message ?: ""), "expected a config hint, got: ${failure.message}")
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Signing-key resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `no signing key means no signature header`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        node.executeForTest(context, inputs)

        assertNull(server.takeRequest().headers["X-Bosca-Signature"], "no signing key -> no signature")
    }

    @Test
    fun `the signing key is resolved from the named secret and signs the body`() = runTest {
        val key = "topsecret"
        registerSecrets(signingKey = key)
        server.enqueue(MockResponse.Builder().code(204).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, signingSecret = SIGN_SECRET)

        node.executeForTest(context, inputs)

        val request = server.takeRequest()
        val sentBody = request.body!!.utf8()
        assertEquals(expectedSignature(key, sentBody), request.headers["X-Bosca-Signature"])
    }

    @Test
    fun `the legacy inline signing key signs the body as a fallback`() = runTest {
        // No signingSecret; the inline secret is used. URL comes from its secret.
        val key = "inline-key"
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, secret = key)

        node.executeForTest(context, inputs)

        val request = server.takeRequest()
        assertEquals(expectedSignature(key, request.body!!.utf8()), request.headers["X-Bosca-Signature"])
    }

    // ---------------------------------------------------------------------------------------------
    // Dry-run trace branch (no HTTP request, resolves nothing)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dry run with a signing secret records signed=true and the URL secret name and makes no request`() = runTest {
        // No PipelineSecretService registered: if the dry run resolved anything, provide<> would throw.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, signingSecret = SIGN_SECRET)

        val result = node.executeForTest(dry, inputs)

        assertNull(result, "a side-effect node returns no output")
        val recorded = trace.actions["wh"]?.jsonObject ?: error("expected the action to be traced")
        assertEquals("sendWebhook", recorded["action"]?.jsonPrimitive?.content)
        assertEquals(URL_SECRET, recorded["urlSecret"]?.jsonPrimitive?.content)
        assertTrue(recorded["signed"]!!.jsonPrimitive.boolean, "signed must be true when a signing key is configured")
        assertEquals(0, server.requestCount, "dry runs must not send any request")
    }

    @Test
    fun `dry run with a legacy inline signing key still records signed=true`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, secret = "inline")

        node.executeForTest(dry, inputs)

        val recorded = trace.actions["wh"]!!.jsonObject
        assertTrue(recorded["signed"]!!.jsonPrimitive.boolean, "signed reflects the inline key too")
    }

    @Test
    fun `dry run without any signing key records signed=false`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        node.executeForTest(dry, inputs)

        val recorded = trace.actions["wh"]!!.jsonObject
        assertFalse(recorded["signed"]!!.jsonPrimitive.boolean, "signed must be false with no key")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `dry run with a null trace records nothing and still makes no request`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = null)
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, signingSecret = SIGN_SECRET)

        val result = node.executeForTest(dry, inputs)

        assertNull(result)
        assertEquals(0, server.requestCount, "dry runs must not send any request even with a null trace")
    }

    @Test
    fun `dry run with a legacy URL omits the urlSecret key`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = SendWebhookNode(id = "wh", url = url)

        node.executeForTest(dry, emptyInputs)

        val recorded = trace.actions["wh"]!!.jsonObject
        assertTrue("urlSecret" !in recorded, "no URL secret configured -> no urlSecret key")
        assertFalse(recorded["signed"]!!.jsonPrimitive.boolean)
    }

    // ---------------------------------------------------------------------------------------------
    // Live 2xx success (body shapes)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `live success posts the encoded body as JSON and returns null`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        val result = node.executeForTest(context, inputs)

        assertNull(result, "a side-effect node returns no output")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(input.encode(Json).toString(), request.body!!.utf8())
        assertNull(request.headers["X-Bosca-Signature"], "no signature without a signing key")
    }

    @Test
    fun `live success with null input posts a JsonNull body`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        node.executeForTest(context, emptyInputs)

        assertEquals(JsonNull.toString(), server.takeRequest().body!!.utf8(), "a missing input posts a JSON null body")
    }

    @Test
    fun `live success with a JSON input posts that JSON verbatim`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(200).build())
        val jsonInput = PipelineValue.ofJson(buildJsonObject { put("hello", "world") })
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET)

        node.executeForTest(context, NodeInputs(mapOf("in" to jsonInput)))

        assertEquals(jsonInput.encode(Json).toString(), server.takeRequest().body!!.utf8())
    }

    // ---------------------------------------------------------------------------------------------
    // Live non-2xx failure — the error identifies the node, never the resolved URL
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `live non-2xx response fails the node by name and does not leak the URL`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(500).build())
        val node = SendWebhookNode(id = "wh", name = "Notify", urlSecret = URL_SECRET)

        val failure = assertFailsWith<IllegalStateException> { node.executeForTest(context, inputs) }

        val message = failure.message ?: ""
        assertTrue("returned HTTP" in message && "500" in message, "expected the HTTP code, got: $message")
        assertTrue("Notify" in message, "the node name should appear, got: $message")
        assertTrue(url !in message, "the resolved URL must never appear in the failure")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `live non-2xx response uses the node id when its name is blank`() = runTest {
        registerSecrets()
        server.enqueue(MockResponse.Builder().code(500).build())
        val failure = assertFailsWith<IllegalStateException> {
            SendWebhookNode(id = "webhook-node", urlSecret = URL_SECRET).executeForTest(context, inputs)
        }
        assertTrue("webhook-node" in failure.message.orEmpty())
    }

    @Test
    fun `live non-2xx with a signing key still signs the request before failing`() = runTest {
        val key = "topsecret"
        registerSecrets(signingKey = key)
        server.enqueue(MockResponse.Builder().code(404).build())
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, signingSecret = SIGN_SECRET)

        val failure = assertFailsWith<IllegalStateException> { node.executeForTest(context, inputs) }
        assertTrue("404" in (failure.message ?: ""))

        val request = server.takeRequest()
        assertEquals(expectedSignature(key, request.body!!.utf8()), request.headers["X-Bosca-Signature"])
    }

    // ---------------------------------------------------------------------------------------------
    // @Serializable data class arms (round-trip + per-field inequality + default deserialization)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `node round-trips through its serializer`() {
        val node = SendWebhookNode(
            id = "wh",
            name = "Notify",
            description = "desc",
            urlSecret = URL_SECRET,
            signingSecret = SIGN_SECRET,
            url = "https://example.test/hook",
            secret = "s3cr3t",
        )
        val encoded = Json.encodeToString(SendWebhookNode.serializer(), node)
        val decoded = Json.decodeFromString(SendWebhookNode.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.urlSecret, decoded.urlSecret)
        assertEquals(node.signingSecret, decoded.signingSecret)
        assertEquals(node.url, decoded.url)
        assertEquals(node.secret, decoded.secret)
    }

    @Test
    fun `minimal JSON decodes using default values`() {
        // Only id is required; everything else takes its default (null / "").
        val node = Json.decodeFromString(SendWebhookNode.serializer(), """{"id":"wh","urlSecret":"$URL_SECRET"}""")
        assertEquals("wh", node.id)
        assertEquals(URL_SECRET, node.urlSecret)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertNull(node.signingSecret)
        assertNull(node.url)
        assertNull(node.secret)
    }

    @Test
    fun `each differing field is observable through the serialized form`() {
        val ser = SendWebhookNode.serializer()
        fun node(
            id: String = "wh", name: String = "Notify", description: String = "desc",
            urlSecret: String? = URL_SECRET, signingSecret: String? = SIGN_SECRET,
            url: String? = "https://example.test/hook", secret: String? = "s3cr3t",
        ) = SendWebhookNode(
            id = id, name = name, description = description,
            urlSecret = urlSecret, signingSecret = signingSecret, url = url, secret = secret,
        )
        val baseJson = Json.encodeToString(ser, node())
        assertTrue(baseJson != Json.encodeToString(ser, node(id = "other")))
        assertTrue(baseJson != Json.encodeToString(ser, node(name = "Other")))
        assertTrue(baseJson != Json.encodeToString(ser, node(description = "other")))
        assertTrue(baseJson != Json.encodeToString(ser, node(urlSecret = "other")))
        assertTrue(baseJson != Json.encodeToString(ser, node(signingSecret = null)))
        assertTrue(baseJson != Json.encodeToString(ser, node(url = "https://other.test/hook")))
        assertTrue(baseJson != Json.encodeToString(ser, node(secret = null)))
    }

    @Test
    fun `body string for a present input is the encoded element string`() {
        val element = input.encode(Json)
        assertTrue(element.jsonObject.containsKey("name"))
        assertEquals(JsonPrimitive("Ada"), element.jsonObject["name"])
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic default-ctor mask arms: PARTIAL subsets of the optional params.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `constructing with id urlSecret and signingSecret leaves name description and position default`() {
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, signingSecret = SIGN_SECRET)
        assertEquals(SIGN_SECRET, node.signingSecret)
        assertEquals("", node.name)
        assertEquals("", node.description)
    }

    @Test
    fun `constructing with id urlSecret and name leaves the other optionals default`() {
        val node = SendWebhookNode(id = "wh", urlSecret = URL_SECRET, name = "Notify")
        assertEquals("Notify", node.name)
        assertNull(node.signingSecret, "signingSecret defaults to null when not provided")
        assertNull(node.url)
        assertNull(node.secret)
    }

    @Test
    fun `constructing with only id leaves every other optional default`() {
        val node = SendWebhookNode(id = "wh")
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertNull(node.urlSecret)
        assertNull(node.signingSecret)
        assertNull(node.url)
        assertNull(node.secret)
    }

    // --- generated deserializer: the throwMissingFieldException arm (the required id absent) ---

    @Test
    fun `decoding JSON missing the required id throws`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(SendWebhookNode.serializer(), "{}")
        }
    }

    private companion object {
        const val URL_SECRET = "hook-url"
        const val SIGN_SECRET = "hook-sign"
    }
}
