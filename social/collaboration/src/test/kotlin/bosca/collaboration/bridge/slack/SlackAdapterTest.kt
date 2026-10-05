package bosca.collaboration.bridge.slack

import bosca.chat.model.ChatMessage
import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgePlatform
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleBinding = BridgeBinding(
        id = UUID.random(),
        channelId = UUID.random(),
        platform = BridgePlatform.SLACK,
        externalChannelId = "C123ABC",
        workspaceId = "T123",
    )

    private fun message(vararg blocks: MessageContent) = ChatMessage(
        sequence = 1L,
        timestamp = OffsetDateTime.now(),
        senderId = UUID.random(),
        content = blocks.toList(),
    )

    /**
     * Builds a [SlackAdapter] backed by a Ktor `MockEngine` that returns
     * the given response body and status. Each request the adapter makes
     * is captured into [requests] for assertion.
     */
    private fun adapterReturning(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        token: String = "xoxb-fake",
    ): Pair<SlackAdapter, MutableList<RecordedRequest>> {
        val requests = mutableListOf<RecordedRequest>()
        val engine = MockEngine { request ->
            val body = request.body.let {
                when (it) {
                    is io.ktor.http.content.TextContent -> it.text
                    is io.ktor.http.content.ByteArrayContent -> it.bytes().toString(Charsets.UTF_8)
                    else -> ""
                }
            }
            requests.add(
                RecordedRequest(
                    url = request.url.toString(),
                    headers = request.headers.entries().associate { it.key to it.value.first() },
                    body = body,
                ),
            )
            respond(
                content = ByteReadChannel(responseBody),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        return SlackAdapter(client, json, tokenProvider = { token }) to requests
    }

    data class RecordedRequest(val url: String, val headers: Map<String, String>, val body: String)

    // ---- sendMessage ----

    @Test
    fun `sendMessage posts a JSON object (not a quoted JSON string) and returns the ts`() {
        val (adapter, requests) = adapterReturning("""{"ok":true,"ts":"1700000000.000100"}""")
        val ts = runBlocking { adapter.sendMessage(sampleBinding, message(MessageContent(MessageContentType.TEXT, "hello")), "Alice") }
        assertEquals("1700000000.000100", ts)
        assertEquals(1, requests.size)
        val req = requests.first()
        assertEquals("https://slack.com/api/chat.postMessage", req.url)
        assertEquals("Bearer xoxb-fake", req.headers["Authorization"])
        // The body must be a real JSON object — not a quoted string. The
        // earlier version of this adapter posted `"{...}"` which Slack
        // would reject; the regression test is asserting the body parses
        // cleanly as an object with the expected keys.
        val parsed = json.parseToJsonElement(req.body).jsonObject
        assertEquals("C123ABC", parsed["channel"]?.jsonPrimitive?.content)
        assertEquals("hello", parsed["text"]?.jsonPrimitive?.content)
        assertEquals("Alice", parsed["username"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sendMessage throws SlackApiException when ok is false`() {
        val (adapter, _) = adapterReturning("""{"ok":false,"error":"channel_not_found"}""")
        val ex = assertFailsWith<SlackApiException> {
            runBlocking { adapter.sendMessage(sampleBinding, message(MessageContent(MessageContentType.TEXT, "hi")), "Alice") }
        }
        assertEquals("channel_not_found", ex.errorCode)
        assertEquals("chat.postMessage", ex.operation)
    }

    @Test
    fun `sendMessage throws SlackApiException when body is not JSON`() {
        val (adapter, _) = adapterReturning("not json")
        val ex = assertFailsWith<SlackApiException> {
            runBlocking { adapter.sendMessage(sampleBinding, message(MessageContent(MessageContentType.TEXT, "hi")), "Alice") }
        }
        assertEquals("invalid_response", ex.errorCode)
    }

    @Test
    fun `sendMessage throws SlackApiException when ok is true but ts is missing`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val ex = assertFailsWith<SlackApiException> {
            runBlocking { adapter.sendMessage(sampleBinding, message(MessageContent(MessageContentType.TEXT, "hi")), "Alice") }
        }
        assertEquals("missing_ts", ex.errorCode)
    }

    // ---- editMessage ----

    @Test
    fun `editMessage targets chat update with channel ts and text`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking {
            adapter.editMessage(sampleBinding, "1700000000.000100",
                message(MessageContent(MessageContentType.TEXT, "edited")), "Alice")
        }
        val req = requests.first()
        assertEquals("https://slack.com/api/chat.update", req.url)
        val parsed = json.parseToJsonElement(req.body).jsonObject
        assertEquals("C123ABC", parsed["channel"]?.jsonPrimitive?.content)
        assertEquals("1700000000.000100", parsed["ts"]?.jsonPrimitive?.content)
        assertEquals("edited", parsed["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `editMessage throws when Slack reports failure`() {
        val (adapter, _) = adapterReturning("""{"ok":false,"error":"message_not_found"}""")
        val ex = assertFailsWith<SlackApiException> {
            runBlocking {
                adapter.editMessage(sampleBinding, "1.000",
                    message(MessageContent(MessageContentType.TEXT, "x")), "Alice")
            }
        }
        assertEquals("message_not_found", ex.errorCode)
    }

    // ---- deleteMessage ----

    @Test
    fun `deleteMessage hits chat delete with channel and ts`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.deleteMessage(sampleBinding, "1.500") }
        val req = requests.first()
        assertEquals("https://slack.com/api/chat.delete", req.url)
        val parsed = json.parseToJsonElement(req.body).jsonObject
        assertEquals("1.500", parsed["ts"]?.jsonPrimitive?.content)
    }

    // ---- addReaction / removeReaction ----

    @Test
    fun `addReaction maps unicode emoji to slack name`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.addReaction(sampleBinding, "1.000", "👍") }
        val req = requests.first()
        assertEquals("https://slack.com/api/reactions.add", req.url)
        val parsed = json.parseToJsonElement(req.body).jsonObject
        assertEquals("thumbsup", parsed["name"]?.jsonPrimitive?.content)
        assertEquals("1.000", parsed["timestamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun `addReaction passes already-named shortcodes through unchanged`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.addReaction(sampleBinding, "1.000", "custom_emoji") }
        val parsed = json.parseToJsonElement(requests.first().body).jsonObject
        assertEquals("custom_emoji", parsed["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `addReaction strips wrapping colons from a shortcode`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.addReaction(sampleBinding, "1.000", ":tada:") }
        val parsed = json.parseToJsonElement(requests.first().body).jsonObject
        assertEquals("tada", parsed["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `addReaction passes through unknown unicode emoji unchanged so Slack can reject explicitly`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.addReaction(sampleBinding, "1.000", "🦾") }
        val parsed = json.parseToJsonElement(requests.first().body).jsonObject
        // Caller passed through; if Slack rejects, decodeOk surfaces it as a SlackApiException.
        assertEquals("🦾", parsed["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `removeReaction hits reactions remove`() {
        val (adapter, requests) = adapterReturning("""{"ok":true}""")
        runBlocking { adapter.removeReaction(sampleBinding, "1.000", "❤️") }
        assertEquals("https://slack.com/api/reactions.remove", requests.first().url)
        val parsed = json.parseToJsonElement(requests.first().body).jsonObject
        assertEquals("heart", parsed["name"]?.jsonPrimitive?.content)
    }

    // ---- formatMessageText ----

    @Test
    fun `formatMessageText concatenates TEXT blocks with newline`() {
        val (adapter, _) = adapterReturning("""{"ok":true,"ts":"1.0"}""")
        val out = adapter.formatMessageText(message(
            MessageContent(MessageContentType.TEXT, "first"),
            MessageContent(MessageContentType.TEXT, "second"),
        ))
        assertEquals("first\nsecond", out)
    }

    @Test
    fun `formatMessageText converts HTML to plain text`() {
        val (adapter, _) = adapterReturning("""{"ok":true,"ts":"1.0"}""")
        val out = adapter.formatMessageText(message(
            MessageContent(MessageContentType.HTML, "<p>hello <strong>world</strong></p>"),
        ))
        assertEquals("hello world", out)
    }

    @Test
    fun `formatMessageText skips non-text content`() {
        val (adapter, _) = adapterReturning("""{"ok":true,"ts":"1.0"}""")
        val out = adapter.formatMessageText(message(
            MessageContent(MessageContentType.IMAGE, "https://example.com/x.png"),
            MessageContent(MessageContentType.TEXT, "captioned"),
        ))
        assertEquals("captioned", out)
    }

    @Test
    fun `formatMessageText returns placeholder for media-only content`() {
        val (adapter, _) = adapterReturning("""{"ok":true,"ts":"1.0"}""")
        val out = adapter.formatMessageText(message(MessageContent(MessageContentType.IMAGE, "x")))
        assertEquals("(empty message)", out)
    }

    // ---- htmlToPlain ----

    @Test
    fun `htmlToPlain decodes common entities`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        assertEquals("a & b < c > \"d\" 'e' f", adapter.htmlToPlain("a &amp; b &lt; c &gt; &quot;d&quot; &#39;e&#39;&nbsp;f"))
    }

    @Test
    fun `htmlToPlain converts br and closing block tags into newlines`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val out = adapter.htmlToPlain("line one<br>line two<br/>line three<p>para</p>")
        assertContains(out, "line one")
        assertContains(out, "line two")
        assertContains(out, "line three")
        assertContains(out, "para")
    }

    // ---- verifyWebhookSignature ----

    private fun signWithSecret(secret: String, ts: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return SlackAdapter.SIGNATURE_PREFIX + mac.doFinal("v0:$ts:$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `verifyWebhookSignature accepts a properly signed body`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val ts = "1700000000"
        val body = "{}"
        val sig = signWithSecret("shhh", ts, body)
        val headers = mapOf("x-slack-request-timestamp" to ts, "x-slack-signature" to sig)
        assertTrue(adapter.verifyWebhookSignature(headers, body.toByteArray(Charsets.UTF_8), "shhh"))
    }

    @Test
    fun `verifyWebhookSignature rejects when body is tampered`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val ts = "1700000000"
        val sig = signWithSecret("shhh", ts, "{}")
        val headers = mapOf("x-slack-request-timestamp" to ts, "x-slack-signature" to sig)
        assertFalse(adapter.verifyWebhookSignature(headers, "tampered".toByteArray(Charsets.UTF_8), "shhh"))
    }

    @Test
    fun `verifyWebhookSignature rejects a missing timestamp header`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val headers = mapOf("x-slack-signature" to "v0=abc")
        assertFalse(adapter.verifyWebhookSignature(headers, "{}".toByteArray(Charsets.UTF_8), "shhh"))
    }

    @Test
    fun `verifyWebhookSignature rejects a missing signature header`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val headers = mapOf("x-slack-request-timestamp" to "1700000000")
        assertFalse(adapter.verifyWebhookSignature(headers, "{}".toByteArray(Charsets.UTF_8), "shhh"))
    }

    @Test
    fun `verifyWebhookSignature rejects a signature missing the v0 prefix`() {
        val (adapter, _) = adapterReturning("""{"ok":true}""")
        val ts = "1700000000"
        val sig = signWithSecret("shhh", ts, "{}").removePrefix(SlackAdapter.SIGNATURE_PREFIX)
        val headers = mapOf("x-slack-request-timestamp" to ts, "x-slack-signature" to sig)
        assertFalse(adapter.verifyWebhookSignature(headers, "{}".toByteArray(Charsets.UTF_8), "shhh"))
    }

    // ---- constantTimeEquals ----

    @Test
    fun `constantTimeEquals matches identical strings`() {
        assertTrue(SlackAdapter.constantTimeEquals("v0=abcdef", "v0=abcdef"))
    }

    @Test
    fun `constantTimeEquals rejects strings of different lengths`() {
        assertFalse(SlackAdapter.constantTimeEquals("v0=abc", "v0=abcdef"))
    }

    @Test
    fun `constantTimeEquals rejects single-character difference`() {
        assertFalse(SlackAdapter.constantTimeEquals("v0=abcdef", "v0=abcdee"))
    }
}
