package bosca.collaboration.service

import bosca.chat.service.ChatService
import bosca.collaboration.federation.FederationMessageEnvelope
import bosca.collaboration.federation.FederationService
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.di.asProvider
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FederationInboundListenerTest {

    private lateinit var pubSubService: PubSubService
    private lateinit var listener: FederationInboundListener

    @BeforeTest
    fun setup() {
        pubSubService = mockk(relaxed = true)
        // The listener init {} schedules a refresh after a delay; for unit
        // tests we don't want any subscriptions opened, so return an empty
        // flow for any subscribe call.
        every { pubSubService.subscribe<Any>(any(), any()) } returns emptyFlow()
        listener = FederationInboundListener(
            pubSubService = pubSubService,
            federationServiceProvider = mockk<FederationService>(relaxed = true).asProvider(),
            chatService = mockk<ChatService>(relaxed = true).asProvider(),
            profileService = mockk<ProfileService>(relaxed = true).asProvider(),
        )
    }

    @Test
    fun `federationSubject formats a stable subject string for a channel`() {
        val channelId = UUID.parse("12345678-1234-5678-1234-567812345678")
        assertEquals("bosca.federation.chat.12345678-1234-5678-1234-567812345678.messages", federationSubject(channelId))
    }

    @Test
    fun `mergeFederationAttributes adds source and origin markers without dropping existing fields`() {
        val original: JsonObject = buildJsonObject {
            put("clientId", JsonPrimitive("c-1"))
            put("priority", JsonPrimitive("normal"))
        }
        val peerId = UUID.random()
        val originChannelId = UUID.random()
        val merged = listener.mergeFederationAttributes(original, peerId, originChannelId)
        assertEquals("c-1", (merged["clientId"] as JsonPrimitive).content)
        assertEquals("normal", (merged["priority"] as JsonPrimitive).content)
        assertEquals("federation", (merged["source"] as JsonPrimitive).content)
        assertEquals(peerId.toString(), (merged["originPeerId"] as JsonPrimitive).content)
        assertEquals(originChannelId.toString(), (merged["originChannelId"] as JsonPrimitive).content)
    }

    @Test
    fun `mergeFederationAttributes handles a null original`() {
        val peerId = UUID.random()
        val originChannelId = UUID.random()
        val merged = listener.mergeFederationAttributes(null, peerId, originChannelId)
        assertEquals("federation", (merged["source"] as JsonPrimitive).content)
        assertNotNull(merged["originPeerId"])
        assertNotNull(merged["originChannelId"])
        assertNull(merged["clientId"])
    }

    @Test
    fun `mergeFederationAttributes overrides a pre-existing source field on the original`() {
        val original = buildJsonObject {
            put("source", JsonPrimitive("user"))
        }
        val merged = listener.mergeFederationAttributes(original, UUID.random(), UUID.random())
        assertEquals("federation", (merged["source"] as JsonPrimitive).content)
    }

    @Test
    fun `federation retries reuse the origin message client id`() {
        val envelope = FederationMessageEnvelope(
            originPeerId = UUID.random(),
            originChannelId = UUID.random(),
            originSenderId = UUID.random(),
            originSequence = 42,
            senderName = "Remote sender",
            content = listOf(MessageContent(MessageContentType.TEXT, "hello")),
        )

        assertEquals(listener.clientIdForEnvelope(envelope), listener.clientIdForEnvelope(envelope.copy()))
        kotlin.test.assertNotEquals(
            listener.clientIdForEnvelope(envelope),
            listener.clientIdForEnvelope(envelope.copy(originSequence = 43)),
        )
    }

    @Test
    fun `SubscriptionKey is value-equal for the same triple`() {
        val peer = UUID.random()
        val remote = UUID.random()
        val local = UUID.random()
        val a = FederationInboundListener.SubscriptionKey(peer, remote, local)
        val b = FederationInboundListener.SubscriptionKey(peer, remote, local)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SubscriptionKey distinguishes by remote channel`() {
        val peer = UUID.random()
        val local = UUID.random()
        val a = FederationInboundListener.SubscriptionKey(peer, UUID.random(), local)
        val b = FederationInboundListener.SubscriptionKey(peer, UUID.random(), local)
        kotlin.test.assertNotEquals(a, b)
    }
}
