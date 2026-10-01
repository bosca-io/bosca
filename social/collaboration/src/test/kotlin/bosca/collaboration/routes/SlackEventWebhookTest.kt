package bosca.collaboration.routes

import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeService
import bosca.di.asProvider
import bosca.security.encryption.EncryptionService
import io.mockk.mockk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackEventWebhookTest {

    private lateinit var webhook: SlackEventWebhook

    @BeforeTest
    fun setup() {
        webhook = SlackEventWebhook(
            bridgeService = mockk<BridgeService>(relaxed = true),
            chatService = mockk<ChatService>(relaxed = true).asProvider(),
            encryptionService = mockk<EncryptionService>(relaxed = true),
        )
    }

    private fun signSlack(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("v0:$timestamp:$body".toByteArray(Charsets.UTF_8))
        return SlackEventWebhook.SIGNATURE_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    // ---- verifySignature ----

    @Test
    fun `verifySignature accepts a correctly-signed recent request`() {
        val secret = "shhh"
        val ts = (System.currentTimeMillis() / 1000).toString()
        val body = "{}"
        val sig = signSlack(secret, ts, body)
        assertTrue(webhook.verifySignature(secret, ts, body, sig))
    }

    @Test
    fun `verifySignature rejects a tampered body`() {
        val secret = "shhh"
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sig = signSlack(secret, ts, "{}")
        assertFalse(webhook.verifySignature(secret, ts, "{\"tampered\":true}", sig))
    }

    @Test
    fun `verifySignature rejects a wrong secret`() {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val body = "{}"
        val sig = signSlack("right", ts, body)
        assertFalse(webhook.verifySignature("wrong", ts, body, sig))
    }

    @Test
    fun `verifySignature rejects a stale timestamp beyond skew`() {
        val secret = "shhh"
        val ts = ((System.currentTimeMillis() / 1000) - SlackEventWebhook.MAX_TIMESTAMP_SKEW_SECONDS - 10).toString()
        val body = "{}"
        val sig = signSlack(secret, ts, body)
        assertFalse(webhook.verifySignature(secret, ts, body, sig))
    }

    @Test
    fun `verifySignature rejects a far-future timestamp beyond skew`() {
        val secret = "shhh"
        val ts = ((System.currentTimeMillis() / 1000) + SlackEventWebhook.MAX_TIMESTAMP_SKEW_SECONDS + 10).toString()
        val body = "{}"
        val sig = signSlack(secret, ts, body)
        assertFalse(webhook.verifySignature(secret, ts, body, sig))
    }

    @Test
    fun `verifySignature rejects a non-numeric timestamp`() {
        val secret = "shhh"
        assertFalse(webhook.verifySignature(secret, "not-a-number", "{}", signSlack(secret, "0", "{}")))
    }

    @Test
    fun `verifySignature rejects a signature missing the v0 prefix`() {
        val secret = "shhh"
        val ts = (System.currentTimeMillis() / 1000).toString()
        val withPrefix = signSlack(secret, ts, "{}")
        val withoutPrefix = withPrefix.removePrefix(SlackEventWebhook.SIGNATURE_PREFIX)
        assertFalse(webhook.verifySignature(secret, ts, "{}", withoutPrefix))
    }

    // ---- parseEvent ----

    @Test
    fun `parseEvent returns null for an empty body`() {
        assertNull(webhook.parseEvent(""))
    }

    @Test
    fun `parseEvent returns null for malformed JSON`() {
        assertNull(webhook.parseEvent("{not json"))
    }

    @Test
    fun `parseEvent returns null for an envelope with no type`() {
        assertNull(webhook.parseEvent("""{"event":{"type":"message"}}"""))
    }

    @Test
    fun `parseEvent reads a url_verification challenge`() {
        val parsed = webhook.parseEvent("""{"type":"url_verification","challenge":"abc"}""")
        assertEquals("url_verification", parsed?.type)
        assertNull(parsed?.event)
    }

    @Test
    fun `parseEvent reads workspace and channel ids on event_callback`() {
        val body = """
            {
              "type": "event_callback",
              "team_id": "T123",
              "event": { "type": "message", "channel": "C456", "user": "U789", "text": "hi" }
            }
        """.trimIndent()
        val parsed = webhook.parseEvent(body)
        assertEquals("event_callback", parsed?.type)
        assertEquals("T123", parsed?.workspaceId)
        assertEquals("C456", parsed?.externalChannelId)
        assertEquals("message", (parsed?.event?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `parseEvent leaves workspace null when team_id absent`() {
        val parsed = webhook.parseEvent("""{"type":"event_callback","event":{"type":"message"}}""")
        assertNull(parsed?.workspaceId)
    }

    @Test
    fun `parseEvent leaves event null when no event field`() {
        val parsed = webhook.parseEvent("""{"type":"event_callback","team_id":"T1"}""")
        assertEquals("event_callback", parsed?.type)
        assertNull(parsed?.event)
    }

    @Test
    fun `SlackHeaders constants match the documented header names`() {
        assertEquals("X-Slack-Request-Timestamp", SlackEventWebhook.SlackHeaders.Timestamp)
        assertEquals("X-Slack-Signature", SlackEventWebhook.SlackHeaders.Signature)
    }
}
