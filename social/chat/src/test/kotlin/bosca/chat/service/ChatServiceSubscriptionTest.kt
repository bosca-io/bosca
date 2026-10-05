@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.chat.service

import bosca.chat.events.CHAT_CHANNEL_MEMBER_REMOVED_TOPIC
import bosca.chat.events.CHAT_PROFILE_UNAVAILABLE_TOPIC
import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.events.ChatProfileUnavailableEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.model.ChatMessage
import bosca.chat.repository.ChatChannelPermissionRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.pubsub.PubSubService
import bosca.pubsub.Message as PubSubMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.PublishOptions
import io.nats.client.impl.NatsJetStreamMetaData
import io.nats.client.api.StreamInfo
import io.nats.client.api.StreamConfiguration
import io.nats.client.api.PublishAck
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatServiceSubscriptionTest {

    @BeforeTest
    fun resetProviders() {
        ProviderRegistry.clear()
    }

    @Test
    fun `client id deduplicates one sender without suppressing another sender`() = runBlocking {
        ProviderRegistry.clear()
        val pubsub = mockk<PubSubService>(relaxed = true)
        provides<Json> { Json }
        provides<PubSubService> { pubsub }

        val physicalConnection = mockk<Connection>()
        val nats = NatsConnectionPool(physicalConnection)
        val management = mockk<JetStreamManagement>()
        val streamInfo = mockk<StreamInfo>()
        val streamConfiguration = mockk<StreamConfiguration>()
        val jetStream = mockk<JetStream>()
        val ack = mockk<PublishAck>()
        val options = mutableListOf<PublishOptions>()
        val channelId = UUID.random()
        val senderId = UUID.random()
        val otherSenderId = UUID.random()
        val clientId = UUID.random()
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))

        every { physicalConnection.jetStreamManagement() } returns management
        every { management.getStreamInfo("CHAT") } returns streamInfo
        every { streamInfo.config } returns streamConfiguration
        every { streamConfiguration.duplicateWindow } returns Duration.ofHours(24)
        every { physicalConnection.jetStream() } returns jetStream
        every {
            jetStream.publish(any<String>(), any<ByteArray>(), capture(options))
        } returns ack
        every { ack.seqno } returnsMany listOf(42L, 42L, 43L)
        every { ack.isDuplicate } returnsMany listOf(false, true, false)
        val securityService = mockk<SecurityService>()
        val service = ChatServiceImpl(
            nats = nats,
            chatChannelRepository = mockk<ChatChannelRepository>(),
            chatChannelPermissionRepository = mockk<ChatChannelPermissionRepository>(),
            reactionStore = mockk<ChatReactionStore>(),
            typingStore = mockk<ChatTypingStore>(),
            presenceStore = mockk<ChatPresenceStore>(),
            readStateStore = mockk<ChatReadStateStore>(),
            profileService = mockk<ProfileService>(),
            securityService = securityService,
            pubSubService = pubsub,
            json = Json,
        )

        try {
            val first = service.sendMessage(channelId, senderId, clientId, content)
            val duplicate = service.sendMessage(channelId, senderId, clientId, content)
            val otherSender = service.sendMessage(channelId, otherSenderId, clientId, content)

            assertEquals(42L, first.sequence)
            assertEquals(false, first.duplicate)
            assertEquals(42L, duplicate.sequence)
            assertEquals(true, duplicate.duplicate)
            assertEquals(43L, otherSender.sequence)
            assertEquals(false, otherSender.duplicate)

            assertEquals(3, options.size)
            assertEquals("$channelId:$senderId:$clientId", options[0].messageId)
            assertEquals(options[0].messageId, options[1].messageId)
            assertEquals("$channelId:$otherSenderId:$clientId", options[2].messageId)
            coVerify(exactly = 2) {
                pubsub.publish(
                    "bosca.chat.v1.message.sent",
                    ChatMessageSentEvent.serializer(),
                    any<ChatMessageSentEvent>(),
                )
            }
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `live subscription acknowledges a message after accepting it for delivery`() = runBlocking {
        installConnectionContext()
        val subscriptionConnection = mockk<Connection>()
        val nats = NatsConnectionPool(subscriptionConnection)
        val management = mockk<JetStreamManagement>()
        val streamInfo = mockk<StreamInfo>()
        val streamConfiguration = mockk<StreamConfiguration>()
        val dispatcher = mockk<Dispatcher>()
        val jetStream = mockk<JetStream>()
        val subscription = mockk<JetStreamSubscription>(relaxed = true)
        val handler = CompletableDeferred<MessageHandler>()
        val channelId = UUID.random()
        val profileId = UUID.random()
        val senderId = UUID.random()
        val clientId = UUID.random()
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))

        every { subscriptionConnection.jetStreamManagement() } returns management
        every { management.getStreamInfo("CHAT") } returns streamInfo
        every { streamInfo.config } returns streamConfiguration
        every { streamConfiguration.duplicateWindow } returns Duration.ofHours(24)
        every { subscriptionConnection.createDispatcher() } returns dispatcher
        every { subscriptionConnection.jetStream() } returns jetStream
        every {
            jetStream.subscribe(any(), dispatcher, any(), false, any())
        } answers {
            handler.complete(thirdArg())
            subscription
        }
        every { dispatcher.subscribe(any<String>(), any<MessageHandler>()) } returns mockk(relaxed = true)
        every { dispatcher.unsubscribe(any<io.nats.client.Subscription>()) } returns dispatcher
        every { subscriptionConnection.flush(any()) } returns Unit
        every { subscriptionConnection.closeDispatcher(dispatcher) } returns Unit

        val message = mockk<Message>(relaxed = true)
        val metadata = mockk<NatsJetStreamMetaData>()
        every { message.data } returns Json.encodeToString(
            ChatMessage.serializer(),
            ChatMessage(
                sequence = 0,
                timestamp = java.time.OffsetDateTime.now(),
                senderId = senderId,
                clientId = clientId,
                content = content,
            ),
        ).toByteArray()
        every { message.metaData() } returns metadata
        every { metadata.streamSequence() } returns 42L
        every { metadata.timestamp() } returns ZonedDateTime.now()

        val securityService = mockk<SecurityService>()
        val removalEvents = MutableSharedFlow<PubSubMessage<ChatChannelMemberRemovedEvent>>()
        val profileUnavailableEvents = MutableSharedFlow<PubSubMessage<ChatProfileUnavailableEvent>>()
        val pubsub = mockk<PubSubService>()
        every {
            pubsub.subscribe(
                CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                ChatChannelMemberRemovedEvent.serializer(),
            )
        } returns removalEvents
        every {
            pubsub.subscribe(
                CHAT_PROFILE_UNAVAILABLE_TOPIC,
                ChatProfileUnavailableEvent.serializer(),
            )
        } returns profileUnavailableEvents
        val channelRepository = mockk<ChatChannelRepository>()
        coEvery { channelRepository.getMember(channelId, profileId) } returns
            bosca.chat.model.ChatChannelMember(channelId, profileId, "member")
        val service = ChatServiceImpl(
            nats = nats,
            chatChannelRepository = channelRepository,
            chatChannelPermissionRepository = mockk<ChatChannelPermissionRepository>(),
            reactionStore = mockk<ChatReactionStore>(),
            typingStore = mockk<ChatTypingStore>(),
            presenceStore = mockk<ChatPresenceStore>(),
            readStateStore = mockk<ChatReadStateStore>(),
            profileService = mockk<ProfileService>(),
            securityService = securityService,
            pubSubService = pubsub,
            json = Json,
        )

        val delivered = async { service.subscribe(channelId, profileId).first() }
        handler.await().onMessage(message)

        assertEquals(42L, withTimeout(5_000) { delivered.await() }.sequence)
        verify(exactly = 1) { message.ack() }
        verify(exactly = 1) { subscriptionConnection.flush(Duration.ofSeconds(5)) }
        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        verify(exactly = 0) { subscriptionConnection.closeDispatcher(dispatcher) }
        nats.close()
        verify(exactly = 1) { subscriptionConnection.closeDispatcher(dispatcher) }
    }

    @Test
    fun `profile deletion closes a live subscription after concurrent stream creation`() = runBlocking {
        installConnectionContext()
        val subscriptionConnection = mockk<Connection>()
        val nats = NatsConnectionPool(subscriptionConnection)
        val management = mockk<JetStreamManagement>()
        val streamNotFound = mockk<JetStreamApiException>()
        val concurrentCreation = mockk<JetStreamApiException>()
        val dispatcher = mockk<Dispatcher>()
        val jetStream = mockk<JetStream>()
        val subscription = mockk<JetStreamSubscription>(relaxed = true)
        val channelId = UUID.random()
        val profileId = UUID.random()
        val subscriptionReady = CompletableDeferred<Unit>()

        every { subscriptionConnection.jetStreamManagement() } returns management
        every { streamNotFound.apiErrorCode } returns 10059
        every { management.getStreamInfo("CHAT") } throws streamNotFound andThen mockk<StreamInfo>()
        every { management.addStream(any()) } throws concurrentCreation
        every { subscriptionConnection.createDispatcher() } returns dispatcher
        every { subscriptionConnection.jetStream() } returns jetStream
        every { jetStream.subscribe(any(), dispatcher, any(), false, any()) } answers {
            subscriptionReady.complete(Unit)
            subscription
        }
        every { dispatcher.subscribe(any<String>(), any<MessageHandler>()) } returns mockk(relaxed = true)
        every { dispatcher.unsubscribe(any<io.nats.client.Subscription>()) } returns dispatcher
        every { subscriptionConnection.flush(any()) } returns Unit
        every { subscriptionConnection.closeDispatcher(dispatcher) } returns Unit
        val securityService = mockk<SecurityService>()
        val removalEvents = MutableSharedFlow<PubSubMessage<ChatChannelMemberRemovedEvent>>()
        val profileUnavailableEvents = MutableSharedFlow<PubSubMessage<ChatProfileUnavailableEvent>>()
        val pubsub = mockk<PubSubService>()
        every {
            pubsub.subscribe(
                CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                ChatChannelMemberRemovedEvent.serializer(),
            )
        } returns removalEvents
        every {
            pubsub.subscribe(
                CHAT_PROFILE_UNAVAILABLE_TOPIC,
                ChatProfileUnavailableEvent.serializer(),
            )
        } returns profileUnavailableEvents
        val channelRepository = mockk<ChatChannelRepository>()
        coEvery { channelRepository.getMember(channelId, profileId) } returns
            bosca.chat.model.ChatChannelMember(channelId, profileId, "member")
        val service = ChatServiceImpl(
            nats = nats,
            chatChannelRepository = channelRepository,
            chatChannelPermissionRepository = mockk<ChatChannelPermissionRepository>(),
            reactionStore = mockk<ChatReactionStore>(),
            typingStore = mockk<ChatTypingStore>(),
            presenceStore = mockk<ChatPresenceStore>(),
            readStateStore = mockk<ChatReadStateStore>(),
            profileService = mockk<ProfileService>(),
            securityService = securityService,
            pubSubService = pubsub,
            json = Json,
        )

        val subscriptionJob = launch {
            service.subscribe(channelId, profileId).collect()
        }
        try {
            withTimeout(5_000) { subscriptionReady.await() }
            assertTrue(subscriptionJob.isActive)
            removalEvents.emit(
                PubSubMessage(
                    CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                    ChatChannelMemberRemovedEvent(UUID.random(), profileId),
                ),
            )
            assertTrue(subscriptionJob.isActive)
            removalEvents.emit(
                PubSubMessage(
                    CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                    ChatChannelMemberRemovedEvent(channelId, UUID.random()),
                ),
            )
            assertTrue(subscriptionJob.isActive)
            profileUnavailableEvents.emit(
                PubSubMessage(
                    CHAT_PROFILE_UNAVAILABLE_TOPIC,
                    ChatProfileUnavailableEvent(profileId),
                ),
            )
            withTimeout(5_000) { subscriptionJob.join() }
        } finally {
            if (subscriptionJob.isActive) subscriptionJob.cancelAndJoin()
        }

        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        verify(exactly = 2) { management.getStreamInfo("CHAT") }
        verify(exactly = 1) { management.addStream(any()) }
        verify(exactly = 1) { subscriptionConnection.flush(Duration.ofSeconds(5)) }
        verify(exactly = 0) { subscriptionConnection.closeDispatcher(dispatcher) }
        nats.close()
        verify(exactly = 1) { subscriptionConnection.closeDispatcher(dispatcher) }
    }

    private fun installConnectionContext() {
        val pool = mockk<ConnectionPool>()
        val manager = mockk<ConnectionManager>()
        coEvery { pool.connection() } returns manager
        coEvery { manager.release() } returns Unit
        provides<ConnectionPool>(singleton = true) { pool }
    }
}
