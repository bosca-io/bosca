package bosca.collaboration.routes

import bosca.chat.service.ChatService
import bosca.chat.model.ChatMessageSendResult
import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.di.asProvider
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.security.encryption.EncryptionService
import bosca.server.HttpStatusCode
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * End-to-end coverage for [SlackEventWebhook.handle] — drives the full
 * Slack-to-Bosca ingestion path with mocked services and asserts both the
 * status code returned to Slack AND the [ChatService] / [BridgeService]
 * calls that should fire as a side effect.
 *
 * The plain-unit tests in [SlackEventWebhookTest] only cover signature
 * verification and JSON parsing. This class exercises the orchestration
 * around them: signing-secret lookup, identity resolution, placeholder
 * fallback, bot echo suppression, and bridge attribute marking.
 */
class SlackEventWebhookExecuteTest {

    private val bridgeService = mockk<BridgeService>(relaxed = true)
    private val chatService = mockk<ChatService>(relaxed = true)
    private val encryptionService = mockk<EncryptionService>(relaxed = true)

    private lateinit var webhook: SlackEventWebhook

    @BeforeTest
    fun setup() {
        webhook = SlackEventWebhook(bridgeService, chatService.asProvider(), encryptionService)
    }

    private fun signSlack(secret: String, body: String, ts: String? = null): Pair<String, String> {
        val timestamp = ts ?: (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("v0:$timestamp:$body".toByteArray(Charsets.UTF_8))
        val sig = SlackEventWebhook.SIGNATURE_PREFIX + digest.joinToString("") { "%02x".format(it) }
        return timestamp to sig
    }

    private fun makeBinding(
        externalChannelId: String = "C09876543",
        workspaceId: String = "T01234567",
    ): BridgeBinding = BridgeBinding(
        id = UUID.parse("00000000-0000-0000-0000-0000000000aa"),
        channelId = UUID.parse("00000000-0000-0000-0000-0000000000bb"),
        platform = BridgePlatform.SLACK,
        externalChannelId = externalChannelId,
        workspaceId = workspaceId,
    )

    // ---- handshake ------------------------------------------------------

    @Test
    fun `url_verification echoes the challenge back without touching ChatService`() = runBlocking {
        val body = """{"type":"url_verification","challenge":"abc-123"}"""

        val result = webhook.handle(body, null, null)

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("abc-123", result.body)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { bridgeService.getBindingByExternal(any(), any(), any()) }
    }

    @Test
    fun `url_verification with no challenge field is rejected as 400`() = runBlocking {
        val result = webhook.handle("""{"type":"url_verification"}""", null, null)
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    // ---- envelope edge cases -------------------------------------------

    @Test
    fun `unparseable JSON returns 400 and never queries the bridge`() = runBlocking {
        val result = webhook.handle("{not-json", null, null)
        assertEquals(HttpStatusCode.BadRequest, result.status)
        coVerify(exactly = 0) { bridgeService.getBindingByExternal(any(), any(), any()) }
    }

    @Test
    fun `empty body returns 400`() = runBlocking {
        val result = webhook.handle("", null, null)
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `non-event_callback envelope returns 200 and is silently dropped`() = runBlocking {
        // Slack sends app_rate_limited, app_uninstalled etc. We ack them so
        // Slack stops retrying but otherwise take no action.
        val result = webhook.handle("""{"type":"app_rate_limited","minute_rate_limited":1234}""", null, null)
        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { bridgeService.getBindingByExternal(any(), any(), any()) }
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `event_callback without team_id returns 400`() = runBlocking {
        val body = """{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi"}}"""
        val result = webhook.handle(body, null, null)
        assertEquals(HttpStatusCode.BadRequest, result.status)
        coVerify(exactly = 0) { bridgeService.getBindingByExternal(any(), any(), any()) }
    }

    @Test
    fun `event_callback without a channel id is acked but skipped`() = runBlocking {
        // No channel means we can't route — still ack so Slack doesn't retry.
        val body = """{"type":"event_callback","team_id":"T1","event":{"type":"message","user":"U1","text":"hi"}}"""
        val result = webhook.handle(body, null, null)
        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { bridgeService.getBindingByExternal(any(), any(), any()) }
    }

    @Test
    fun `event_callback for an unbound channel is acked without further lookups`() = runBlocking {
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, "Cunknown", "T1") } returns null
        val body = """{"type":"event_callback","team_id":"T1","event":{"type":"message","channel":"Cunknown","user":"U1","text":"hi"}}"""

        val result = webhook.handle(body, null, null)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { bridgeService.resolveProfile(any(), any(), any()) }
    }

    // ---- signature handling --------------------------------------------

    @Test
    fun `event_callback rejects requests with a bad signature when a signing secret is configured`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "real-secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hi"}}"""
        val (ts, sig) = signSlack("WRONG-secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.Unauthorized, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `event_callback rejects requests with no timestamp when a signing secret is configured`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "real-secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hi"}}"""

        val result = webhook.handle(body, timestamp = null, signature = "v0=deadbeef")

        assertEquals(HttpStatusCode.Unauthorized, result.status)
    }

    @Test
    fun `event_callback proceeds without signing secret but logs a warning`() = runBlocking {
        // When no signing secret is configured we don't reject — this matches
        // the dev / socket-mode setups documented in the webhook KDoc.
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns null
        coEvery { bridgeService.resolveProfile(BridgePlatform.SLACK, "U1", binding.workspaceId) } returns null

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hi","ts":"1700.001"}}"""

        val result = webhook.handle(body, null, null)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 1) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    // ---- ingestion happy path ------------------------------------------

    @Test
    fun `mapped sender posts under the resolved profile id with bridge-source attributes`() = runBlocking {
        val binding = makeBinding()
        val profileId = UUID.parse("00000000-0000-0000-0000-0000000000ee")
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"
        coEvery { bridgeService.resolveProfile(BridgePlatform.SLACK, "U1", binding.workspaceId) } returns profileId

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hello","ts":"1700.001"}}"""
        val (ts, sig) = signSlack("secret", body)

        val channelSlot = slot<UUID>()
        val senderSlot = slot<UUID>()
        val contentSlot = slot<List<MessageContent>>()
        val attributesSlot = slot<kotlinx.serialization.json.JsonElement>()
        coEvery {
            chatService.sendMessage(
                channelId = capture(channelSlot),
                senderId = capture(senderSlot),
                clientId = any(),
                content = capture(contentSlot),
                attributes = capture(attributesSlot),
                parentSequence = any(),
            )
        } returns ChatMessageSendResult(sequence = 1, duplicate = false)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(binding.channelId, channelSlot.captured)
        assertEquals(profileId, senderSlot.captured)
        assertEquals(MessageContentType.TEXT, contentSlot.captured.single().type)
        assertEquals("hello", contentSlot.captured.single().content)

        // Bridge marker attributes — vital for echo suppression.
        val attrs = attributesSlot.captured as JsonObject
        assertEquals("bridge", attrs["source"]?.jsonPrimitive?.content)
        assertEquals("slack", attrs["platform"]?.jsonPrimitive?.content)
        assertEquals(binding.workspaceId, attrs["workspaceId"]?.jsonPrimitive?.content)
        assertEquals("1700.001", attrs["externalTs"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unmapped sender falls back to PLACEHOLDER_SENDER_ID rather than dropping the message`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"
        coEvery { bridgeService.resolveProfile(BridgePlatform.SLACK, "Unmapped", binding.workspaceId) } returns null

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"Unmapped","text":"orphan ping"}}"""
        val (ts, sig) = signSlack("secret", body)

        val senderSlot = slot<UUID>()
        coEvery {
            chatService.sendMessage(any(), capture(senderSlot), any(), any(), any(), any())
        } returns ChatMessageSendResult(sequence = 7, duplicate = false)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(SlackEventWebhook.PLACEHOLDER_SENDER_ID, senderSlot.captured)
    }

    @Test
    fun `bot_id messages are dropped to avoid echoing our own outbound posts back into the channel`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hi","bot_id":"B999"}}"""
        val (ts, sig) = signSlack("secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { bridgeService.resolveProfile(any(), any(), any()) }
    }

    @Test
    fun `non-message events such as reactions are acked without touching ChatService`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"reaction_added","channel":"${binding.externalChannelId}","user":"U1","reaction":"+1"}}"""
        val (ts, sig) = signSlack("secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `messages with no user are skipped silently with a 200`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","text":"hi"}}"""
        val (ts, sig) = signSlack("secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `messages with empty text are skipped silently with a 200`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":""}}"""
        val (ts, sig) = signSlack("secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `chatService failure returns 503 so Slack retries the stable client id`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"
        coEvery { bridgeService.resolveProfile(any(), any(), any()) } returns null
        coEvery { chatService.sendMessage(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("nats down")

        val body = """{"type":"event_callback","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"hi"}}"""
        val (ts, sig) = signSlack("secret", body)

        val result = webhook.handle(body, ts, sig)

        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
    }

    @Test
    fun `Slack retry reuses its client id while distinct events receive distinct ids`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingByExternal(BridgePlatform.SLACK, binding.externalChannelId, binding.workspaceId) } returns binding
        coEvery { bridgeService.getBotToken(binding.id) } returns "secret"
        coEvery { bridgeService.resolveProfile(any(), any(), any()) } returns null

        val seen = mutableListOf<Uuid>()
        coEvery {
            chatService.sendMessage(
                channelId = any(),
                senderId = any(),
                clientId = any(),
                content = any(),
                attributes = any(),
                parentSequence = any(),
            )
        } answers {
            seen += arg<UUID>(2)
            ChatMessageSendResult(sequence = 1, duplicate = false)
        }

        suspend fun ingest(eventId: String, text: String) {
            val body = """{"type":"event_callback","event_id":"$eventId","team_id":"${binding.workspaceId}","event":{"type":"message","channel":"${binding.externalChannelId}","user":"U1","text":"$text"}}"""
            val (ts, sig) = signSlack("secret", body)
            val result = webhook.handle(body, ts, sig)
            assertEquals(HttpStatusCode.OK, result.status)
        }

        ingest("Ev-1", "same message")
        ingest("Ev-1", "same message")
        ingest("Ev-2", "another message")

        assertEquals(seen[0], seen[1], "a retried Slack event must reuse its clientId")
        kotlin.test.assertNotEquals(seen[0], seen[2], "distinct Slack events must not deduplicate")
    }

    // ---- ParsedEvent surface check -------------------------------------

    @Test
    fun `ParsedEvent payload still exposes the original challenge field for handshake replies`() {
        val parsed = webhook.parseEvent("""{"type":"url_verification","challenge":"xyz"}""")
        assertNotNull(parsed)
        val challenge = parsed.payload["challenge"]
        assertNotNull(challenge)
        assertEquals("xyz", (challenge as JsonPrimitive).content)
        assertNull(parsed.event)
    }
}
