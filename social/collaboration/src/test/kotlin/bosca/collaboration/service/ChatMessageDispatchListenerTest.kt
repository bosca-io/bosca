package bosca.collaboration.service

import bosca.chat.events.ChatMessageSentEvent
import bosca.serialization.OffsetDateTime
import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeService
import bosca.collaboration.federation.FederationService
import bosca.di.asProvider
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the pure decision functions on [ChatMessageDispatchListener]. The
 * `handle` orchestration is intentionally not unit-tested here because the
 * KSP-generated `dispatch()` extensions reach into the global DI registry
 * for the event manager, which isn't wired up in unit tests. Integration
 * coverage of the full path lives alongside the bosca-server tests.
 */
class ChatMessageDispatchListenerTest {

    private lateinit var pubSubService: PubSubService
    private lateinit var listener: ChatMessageDispatchListener

    private val channelId = UUID.random()
    private val senderId = UUID.random()

    @BeforeTest
    fun setup() {
        pubSubService = mockk(relaxed = true)
        // The init {} block subscribes to the pubsub topic. Hand it an empty
        // flow so the subscription completes immediately and doesn't keep a
        // coroutine alive across tests.
        every { pubSubService.subscribe<ChatMessageSentEvent>(any(), any()) } returns emptyFlow()
        listener = ChatMessageDispatchListener(
            pubSubService = pubSubService,
            mentionService = mockk(relaxed = true),
            bridgeService = mockk(relaxed = true),
            federationService = mockk<FederationService>(relaxed = true).asProvider(),
            chatService = mockk<ChatService>(relaxed = true).asProvider(),
            profileService = mockk<ProfileService>(relaxed = true).asProvider(),
        )
    }

    private fun event(content: List<MessageContent>, attributes: JsonObject? = null): ChatMessageSentEvent =
        ChatMessageSentEvent(
            channelId = channelId,
            senderId = senderId,
            sequence = 1L,
            content = content,
            sentAt = OffsetDateTime.now(),
            attributes = attributes,
        )

    // ---- parseKitSlashCommand ----

    @Test
    fun `parseKitSlashCommand returns the verb for slash kit`() {
        val parsed = listener.parseKitSlashCommand(
            listOf(MessageContent(MessageContentType.TEXT, "/kit summarize")),
        )
        assertEquals("summarize", parsed)
    }

    @Test
    fun `parseKitSlashCommand returns null for non-kit slash agent`() {
        assertNull(
            listener.parseKitSlashCommand(
                listOf(MessageContent(MessageContentType.TEXT, "/buddy hi")),
            ),
        )
    }

    @Test
    fun `parseKitSlashCommand returns null for non-slash content`() {
        assertNull(
            listener.parseKitSlashCommand(
                listOf(MessageContent(MessageContentType.TEXT, "kit do something")),
            ),
        )
    }

    @Test
    fun `parseKitSlashCommand is case-insensitive on the agent name`() {
        val parsed = listener.parseKitSlashCommand(
            listOf(MessageContent(MessageContentType.TEXT, "/KIT translate fr")),
        )
        assertEquals("translate", parsed)
    }

    @Test
    fun `parseKitSlashCommand returns null when content is empty`() {
        assertNull(listener.parseKitSlashCommand(emptyList()))
    }

    @Test
    fun `parseKitSlashCommand returns null for slash kit with no command`() {
        assertNull(
            listener.parseKitSlashCommand(
                listOf(MessageContent(MessageContentType.TEXT, "/kit")),
            ),
        )
    }

    // ---- containsKitMention ----

    @Test
    fun `containsKitMention detects at-kit at start of text`() {
        assertTrue(
            listener.containsKitMention(
                listOf(MessageContent(MessageContentType.TEXT, "@kit help me out")),
            ),
        )
    }

    @Test
    fun `containsKitMention detects at-kit mid-text after whitespace`() {
        assertTrue(
            listener.containsKitMention(
                listOf(MessageContent(MessageContentType.TEXT, "hey @kit can you help")),
            ),
        )
    }

    @Test
    fun `containsKitMention is case-insensitive`() {
        assertTrue(
            listener.containsKitMention(
                listOf(MessageContent(MessageContentType.TEXT, "yo @KiT")),
            ),
        )
    }

    @Test
    fun `containsKitMention returns false when at-kit is part of an email-like string`() {
        assertFalse(
            listener.containsKitMention(
                listOf(MessageContent(MessageContentType.TEXT, "user@kitmail.example")),
            ),
        )
    }

    @Test
    fun `containsKitMention scans HTML content too`() {
        assertTrue(
            listener.containsKitMention(
                listOf(MessageContent(MessageContentType.HTML, "<p>@kit help</p>")),
            ),
        )
    }

    @Test
    fun `containsKitMention ignores non-text content blocks`() {
        assertFalse(
            listener.containsKitMention(
                listOf(
                    MessageContent(MessageContentType.IMAGE, "@kit"),
                    MessageContent(MessageContentType.METADATA, "@kit"),
                ),
            ),
        )
    }

    @Test
    fun `containsKitMention returns false on empty content`() {
        assertFalse(listener.containsKitMention(emptyList()))
    }

    // ---- originatedFromBridge ----

    @Test
    fun `originatedFromBridge true when source attribute equals bridge`() {
        val attrs = buildJsonObject { put("source", JsonPrimitive("bridge")) }
        assertTrue(listener.originatedFromBridge(event(emptyList(), attrs)))
    }

    @Test
    fun `originatedFromBridge false when source attribute is something else`() {
        val attrs = buildJsonObject { put("source", JsonPrimitive("user")) }
        assertFalse(listener.originatedFromBridge(event(emptyList(), attrs)))
    }

    @Test
    fun `originatedFromBridge false when attributes are missing`() {
        assertFalse(listener.originatedFromBridge(event(emptyList(), null)))
    }

    @Test
    fun `originatedFromBridge false when source attribute is missing`() {
        val attrs = buildJsonObject { put("platform", JsonPrimitive("slack")) }
        assertFalse(listener.originatedFromBridge(event(emptyList(), attrs)))
    }

    // ---- originatedFromFederation ----

    @Test
    fun `originatedFromFederation true when source attribute equals federation`() {
        val attrs = buildJsonObject { put("source", JsonPrimitive("federation")) }
        assertTrue(listener.originatedFromFederation(event(emptyList(), attrs)))
    }

    @Test
    fun `originatedFromFederation false when source attribute is bridge`() {
        val attrs = buildJsonObject { put("source", JsonPrimitive("bridge")) }
        assertFalse(listener.originatedFromFederation(event(emptyList(), attrs)))
    }

    @Test
    fun `originatedFromFederation false when attributes are missing`() {
        assertFalse(listener.originatedFromFederation(event(emptyList(), null)))
    }

    @Test
    fun `originatedFromBridge and originatedFromFederation are mutually exclusive on the source marker`() {
        val bridgeAttrs = buildJsonObject { put("source", JsonPrimitive("bridge")) }
        val fedAttrs = buildJsonObject { put("source", JsonPrimitive("federation")) }
        assertTrue(listener.originatedFromBridge(event(emptyList(), bridgeAttrs)))
        assertFalse(listener.originatedFromFederation(event(emptyList(), bridgeAttrs)))
        assertFalse(listener.originatedFromBridge(event(emptyList(), fedAttrs)))
        assertTrue(listener.originatedFromFederation(event(emptyList(), fedAttrs)))
    }
}
