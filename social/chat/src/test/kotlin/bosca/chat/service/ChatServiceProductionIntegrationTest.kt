@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.chat.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.nats.NatsCacheManager
import bosca.cache.withRequestCache
import bosca.chat.events.CHAT_MESSAGE_SENT_TOPIC
import bosca.chat.events.CHAT_CHANNEL_MEMBER_REMOVED_TOPIC
import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessage
import bosca.chat.repository.ChatChannelPermissionRepositoryImpl
import bosca.chat.repository.ChatChannelInvitationRepositoryImpl
import bosca.chat.repository.ChatChannelRepositoryImpl
import bosca.chat.repository.ChatMigration
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.Event
import bosca.events.eventManager
import bosca.events.withEventManager
import bosca.nats.NatsConnectionPool
import bosca.pipelines.PipelineEventDispatcher
import bosca.profile.profile.service.ProfileService
import bosca.profile.organization.service.OrganizationService
import bosca.profile.persona.repository.ProfileMigration
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.repository.GroupRepositoryImpl
import bosca.security.repository.PrincipalCredentialsRepositoryImpl
import bosca.security.repository.PrincipalEmailRepositoryImpl
import bosca.security.repository.PrincipalExchangeTokenRepositoryImpl
import bosca.security.repository.PrincipalGroupRepositoryImpl
import bosca.security.repository.PrincipalRefreshTokenRepositoryImpl
import bosca.security.repository.PrincipalRepositoryImpl
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.PasswordEncoder
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.SecurityServiceImpl
import bosca.security.service.ThirdPartyTokenVerifier
import bosca.serialization.UUID
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.mockk
import io.nats.client.JetStreamApiException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Production-boundary tests for chat delivery. PostgreSQL repositories, NATS JetStream, NATS
 * pub/sub, and the NATS job queue are real. Strict test doubles are used only for constructor
 * dependencies that these scenarios must not reach; an unexpected call therefore fails the test.
 */
class ChatServiceProductionIntegrationTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_chat_production_integration_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 8,
                ),
                key = "chat-production-integration-test",
            ),
        )
        private val natsContainer = SharedNatsContainer().apply { start() }
        private val nats = natsContainer.newConnectionPool(12)
        private val cacheManager = NatsCacheManager(nats)
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                nats.close()
                pool.close()
            }
            natsContainer.stop()
            postgres.stop()
        }
    }

    private val channelRepository = ChatChannelRepositoryImpl()
    private val permissionRepository = ChatChannelPermissionRepositoryImpl()
    private val pubSub = NatsPubSubServiceImpl(json, nats)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<NatsConnectionPool>(singleton = true) { nats }
        provides<bosca.pubsub.PubSubService>(singleton = true) { pubSub }
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { RequestCacheSerializerImpl(json) }
        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration(), ProfileMigration(), ChatMigration()))
            }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("delete from chat.channel_invitations") { it.execute() }
                connection().useStatement("delete from chat.channel_members") { it.execute() }
                connection().useStatement("delete from chat.channel_permissions") { it.execute() }
                connection().useStatement("delete from chat.channels") { it.execute() }
            }
        }
    }

    @Test
    fun `subscription collected after request connection scope reads membership from PostgreSQL`() = runBlocking {
        val profileId = seedProfile()
        val channelId = seedChannel(profileId, ChatChannelType.GROUP)
        val service = realDeliveryService()

        // This mirrors GraphQL: authorization completes inside a request connection scope, while
        // the returned Flow is collected later by the subscription machinery.
        val initialConsumerCount = chatConsumerCount()
        val subscription = withDb {
            assertEquals(channelId, service.getById(channelId)?.id)
            service.subscribe(channelId, profileId)
        }
        val received = async { subscription.first() }

        waitForChatConsumer(initialConsumerCount)
        val expected = publishMessage(channelId)

        val actual = withTimeout(10_000) { received.await() }
        // Live delivery uses JetStream's persisted timestamp as the authoritative sent time.
        assertEquals(expected.copy(timestamp = actual.timestamp), actual)
    }

    @Test
    fun `public channel type does not bypass profile membership for realtime subscriptions`() = runBlocking {
        val profileId = seedProfile()
        val channelId = withDb {
            transaction {
                channelRepository.createChannel(null, "Public", ChatChannelType.PUBLIC, null).id
            }
        }

        val events = realDeliveryService().subscribe(channelId, profileId).toList()

        assertTrue(events.isEmpty())
        assertEquals(null, withDb { channelRepository.getMember(channelId, profileId) })
    }

    @Test
    fun `removing profile membership closes a live subscription through real pubsub`() = runBlocking {
        val profileId = seedProfile()
        val channelId = seedChannel(profileId, ChatChannelType.GROUP)
        val service = realDeliveryService()
        val initialConsumerCount = chatConsumerCount()
        val collected = async { service.subscribe(channelId, profileId).collect() }

        waitForChatConsumer(initialConsumerCount)
        withDb {
            assertEquals(1, transaction { channelRepository.removeMember(channelId, profileId) })
        }
        pubSub.publish(
            CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
            ChatChannelMemberRemovedEvent.serializer(),
            ChatChannelMemberRemovedEvent(channelId, profileId),
        )

        withTimeout(10_000) { collected.await() }
    }

    @Test
    fun `duplicate client id stores and dispatches a message event to pipelines only once`() = runBlocking {
        val service = realDeliveryService()
        val channelId = UUID.random()
        val senderId = UUID.random()
        val clientId = UUID.random()
        val content = listOf(MessageContent(MessageContentType.TEXT, "idempotent delivery"))

        val pipelineEvents = mutableListOf<Pair<String, Event>>()
        provides<PipelineEventDispatcher>(singleton = true) {
            object : PipelineEventDispatcher {
                override suspend fun <T : Event> dispatch(
                    eventName: String,
                    event: T,
                    serializer: KSerializer<T>,
                ) {
                    pipelineEvents += eventName to event
                }
            }
        }

        val event = async {
            withTimeout(10_000) {
                pubSub.subscribe(CHAT_MESSAGE_SENT_TOPIC, ChatMessageSentEvent.serializer()).first().message
            }
        }
        delay(300)

        val first = service.sendMessage(channelId, senderId, clientId, content)
        val duplicate = service.sendMessage(channelId, senderId, clientId, content)
        val dispatched = event.await()
        val pipelineEvent = pipelineEvents.single()
        val notification = pipelineEvent.second as ChatMessageSentEvent

        assertEquals(channelId, dispatched.channelId)
        assertEquals(first.sequence, dispatched.sequence)
        assertEquals(senderId, dispatched.senderId)
        assertEquals(ChatMessageSentEvent::class.qualifiedName, pipelineEvent.first)
        assertEquals(first.sequence, notification.sequence)
        assertEquals(false, first.duplicate)
        assertEquals(first.sequence, duplicate.sequence)
        assertEquals(true, duplicate.duplicate)
        assertEquals(1, service.getMessages(channelId, limit = 10).size)
    }

    @Test
    fun `new reaction dispatches one notification event with the message author`() = runBlocking {
        val service = realDeliveryService()
        val channelId = UUID.random()
        val reactorId = UUID.random()
        service.getMessages(channelId, limit = 1)
        val message = publishMessage(channelId)
        val pipelineEvents = mutableListOf<Pair<String, Event>>()
        provides<PipelineEventDispatcher>(singleton = true) {
            object : PipelineEventDispatcher {
                override suspend fun <T : Event> dispatch(
                    eventName: String,
                    event: T,
                    serializer: KSerializer<T>,
                ) {
                    pipelineEvents += eventName to event
                }
            }
        }

        service.addReaction(channelId, message.sequence, reactorId, "👍")
        service.addReaction(channelId, message.sequence, reactorId, "👍")

        val event = pipelineEvents.single().second as ChatMessageReactionAddedEvent
        assertEquals(ChatMessageReactionAddedEvent::class.qualifiedName, pipelineEvents.single().first)
        assertEquals(channelId, event.channelId)
        assertEquals(message.sequence, event.sequence)
        assertEquals(reactorId, event.reactorId)
        assertEquals(message.senderId, event.messageAuthorId)
        assertEquals("👍", event.emoji)
        val storedReaction = ChatReactionStore(nats).getForMessage(channelId, message.sequence).single()
        assertEquals(event.reactionId, storedReaction.id)
        assertEquals("👍", storedReaction.emoji)
    }

    @Test
    fun `deleting a channel message removes its content and reactions and emits a live tombstone`() = runBlocking {
        val profileId = seedProfile()
        val channelId = seedChannel(profileId, ChatChannelType.GROUP)
        val otherChannelId = seedChannel(profileId, ChatChannelType.GROUP)
        val service = realDeliveryService()
        service.getMessages(channelId, limit = 1)
        val published = publishMessage(channelId)
        val reactionStore = ChatReactionStore(nats)
        reactionStore.add(channelId, published.sequence, profileId, UUID.random(), "thumbsup")

        assertNull(service.getMessage(otherChannelId, published.sequence))
        assertFalse(service.deleteMessage(otherChannelId, published.sequence))
        assertEquals(published.senderId, service.getMessage(channelId, published.sequence)?.senderId)

        val initialConsumerCount = chatConsumerCount()
        val tombstone = async { service.subscribe(channelId, profileId).first { it.deleted } }
        waitForChatConsumer(initialConsumerCount)

        assertTrue(service.deleteMessage(channelId, published.sequence))
        val deleted = withTimeout(10_000) { tombstone.await() }

        assertEquals(published.sequence, deleted.sequence)
        assertEquals(published.senderId, deleted.senderId)
        assertTrue(deleted.deleted)
        assertTrue(deleted.content.isEmpty())
        assertNull(service.getMessage(channelId, published.sequence))
        assertTrue(reactionStore.getForMessage(channelId, published.sequence).isEmpty())
        assertFalse(service.deleteMessage(channelId, published.sequence))
    }

    @Test
    fun `live message is delivered before its tombstone`() = runBlocking {
        val profileId = seedProfile()
        val channelId = seedChannel(profileId, ChatChannelType.GROUP)
        val service = realDeliveryService()
        val initialConsumerCount = chatConsumerCount()
        val received = async { service.subscribe(channelId, profileId).take(2).toList() }
        waitForChatConsumer(initialConsumerCount)

        val published = publishMessage(channelId)
        assertTrue(service.deleteMessage(channelId, published.sequence))
        val events = withTimeout(10_000) { received.await() }

        assertEquals(2, events.size)
        assertEquals(published.sequence, events[0].sequence)
        assertFalse(events[0].deleted)
        assertEquals(published.sequence, events[1].sequence)
        assertTrue(events[1].deleted)
    }

    @Test
    fun `profile cleanup removes retained membership and its real ACL projection after unlink`() = runBlocking {
        val profileId = seedProfile()
        val channelId = seedChannel(profileId, ChatChannelType.GROUP)
        val principalId = inDb {
            connection().useStatement("select principal from public.profiles where id = '$profileId'") { statement ->
                statement.executeQuery().use { results ->
                    check(results.next())
                    UUID.parse(results.getString(1))
                }
            }
        }
        val securityService = realSecurityService()
        val users = inDb {
            securityService.addGroup(
                Group(
                    name = ChatChannelSecurityGroups.usersName(channelId),
                    description = "Users",
                    type = GroupType.SYSTEM,
                ),
            ).also { securityService.addPrincipalGroup(principalId, it.id) }
        }
        inDb {
            connection().useStatement("update public.profiles set principal = null where id = '$profileId'") {
                it.executeUpdate()
            }
        }
        val handler = ChatProfileCleanupHandler(
            channelRepository,
            ChatChannelInvitationRepositoryImpl(),
            securityService,
            ChatReactionStore(nats),
            ChatReadStateStore(nats),
            ChatTypingStore(nats),
            ChatPresenceStore(nats),
        )

        inDb { handler.onProfileCleanup(profileId, principalId) }

        assertNull(inDb { channelRepository.getMember(channelId, profileId) })
        assertTrue(users.id !in inDb { principalGroupIds(principalId) })
    }

    private fun realSecurityService(): SecurityService = SecurityServiceImpl(
        groupRepository = GroupRepositoryImpl(),
        principalRepository = PrincipalRepositoryImpl(),
        principalGroupsRepository = PrincipalGroupRepositoryImpl(),
        principalRefreshTokensRepository = PrincipalRefreshTokenRepositoryImpl(),
        principalExchangeTokenRepository = PrincipalExchangeTokenRepositoryImpl(),
        credentialsRepository = PrincipalCredentialsRepositoryImpl(),
        json = json,
        argonPasswordEncoder = mockk<PasswordEncoder<ArgonPassword>>(),
        scryptPasswordEncoder = mockk<bosca.di.ObjectProvider<PasswordEncoder<ScryptPassword>>>(),
        securityConfiguration = mockk<bosca.di.ObjectProvider<SecurityConfiguration>>(),
        profileService = mockk<bosca.di.ObjectProvider<ProfileService>>(),
        organizationService = mockk<bosca.di.ObjectProvider<OrganizationService>>(),
        communityService = mockk(),
        attributeVerificationService = mockk(),
        principalEmailRepository = PrincipalEmailRepositoryImpl(),
        thirdPartyTokenVerifier = mockk<ThirdPartyTokenVerifier>(),
        pubSubService = pubSub,
    )

    private fun realDeliveryService(): ChatServiceImpl {
        // These strict doubles are outside the exercised delivery paths. If production code reaches
        // either one, MockK throws instead of allowing the integration test to manufacture success.
        val securityService = mockk<SecurityService>()
        return ChatServiceImpl(
            nats = nats,
            chatChannelRepository = channelRepository,
            chatChannelPermissionRepository = permissionRepository,
            reactionStore = ChatReactionStore(nats),
            typingStore = ChatTypingStore(nats),
            presenceStore = ChatPresenceStore(nats),
            readStateStore = ChatReadStateStore(nats),
            profileService = mockk<ProfileService>(),
            securityService = securityService,
            pubSubService = pubSub,
            json = json,
        )
    }

    private fun seedProfile(): UUID = withDb {
        val principalId = UUID.random()
        val profileId = UUID.random()
        transaction {
            connection().useStatement("insert into public.principals (id) values ('$principalId')") { it.execute() }
            connection().useStatement(
                "insert into public.profiles (id, principal, name) values ('$profileId', '$principalId', 'Subscriber')"
            ) { it.execute() }
        }
        profileId
    }

    private fun seedChannel(profileId: UUID, type: ChatChannelType): UUID = withDb {
        val existing = connection().useStatement(
            "select principal from public.profiles where id = '$profileId'"
        ) { statement ->
            statement.executeQuery().use { results -> results.next() }
        }
        if (!existing) error("profile $profileId does not exist")
        transaction {
            val channel = channelRepository.createChannel(null, "Integration", type, null)
            channelRepository.addMemberIfAbsent(channel.id, profileId, "member")
            channel.id
        }
    }

    private suspend fun waitForChatConsumer(previousCount: Long) {
        withTimeout(10_000) {
            while (chatConsumerCount() <= previousCount) {
                delay(50)
            }
        }
    }

    private suspend fun chatConsumerCount(): Long {
        val connection = nats.systemConnection()
        return try {
            connection.jetStreamManagement().getStreamInfo("CHAT").streamState.consumerCount
        } catch (_: JetStreamApiException) {
            0
        }
    }

    private suspend fun publishMessage(channelId: UUID): ChatMessage {
        val message = ChatMessage(
            sequence = 0,
            timestamp = java.time.OffsetDateTime.now(),
            senderId = UUID.random(),
            clientId = UUID.random(),
            content = listOf(MessageContent(MessageContentType.TEXT, "real message")),
        )
        val subject = "bosca.chat.v1.channels.$channelId.messages"
        val connection = nats.openConnection(subject)
        try {
            val ack = connection.withConnection { physicalConnection ->
                physicalConnection.jetStream().publish(
                    subject,
                    json.encodeToString(ChatMessage.serializer(), message).toByteArray(),
                )
            }
            return message.copy(sequence = ack.seqno)
        } finally {
            connection.close()
        }
    }

    private suspend fun principalGroupIds(principalId: UUID): Set<UUID> =
        connection().useStatement(
            "select group_id from public.principal_groups where principal = '$principalId'"
        ) { statement ->
            statement.executeQuery().use { results ->
                buildSet {
                    while (results.next()) add(UUID.parse(results.getString(1)))
                }
            }
        }

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        inDb(block)
    }

    private suspend fun <T> inDb(block: suspend () -> T): T {
        val manager = pool.connection()
        try {
            return withContext(manager.asCoroutineContext()) {
                withRequestCache { block() }
            }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
