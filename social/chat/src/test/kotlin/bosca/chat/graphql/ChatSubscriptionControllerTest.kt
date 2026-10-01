package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageEvent
import bosca.chat.model.MessageReactionEvent
import bosca.chat.model.PresenceUpdateEvent
import bosca.chat.model.UserTypingEvent
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

@OptIn(InternalDI::class, ExperimentalUuidApi::class)
class ChatSubscriptionControllerTest {

    private val chatService = mockk<ChatService>()
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val connectionPool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val authContext = mockk<AuthenticationContext>()
    private val profileService = mockk<ProfileService>()
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)
    private val profile = Profile(
        id = UUID.random(),
        name = "Subscriber",
        type = ProfileType.GENERIC,
        visibility = ProfileVisibility.PUBLIC,
    )

    private val testChannelId = UUID.random()

    private lateinit var controller: ChatSubscriptionController

    @BeforeTest
    fun setup() {
        every { connectionPool.connection() } returns connectionManager
        every { authContext.principal() } returns
            AuthenticatedPrincipal(Principal(id = UUID.random()), emptyList())
        coEvery { profileService.getPrimaryProfile(any()) } returns profile
        coEvery { chatService.canParticipate(profile.id) } returns true

        ProviderRegistry.register(
            ConnectionPool::class,
            object : ObjectProvider<ConnectionPool> {
                override val type = ConnectionPool::class
                override suspend fun get() = connectionPool
            }
        )

        controller = ChatSubscriptionController(
            chatService,
            chatChannelPermissionEvaluator,
            profileService,
            groupEvaluator,
            chatObjectPermissionEvaluator,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun testChannel(id: UUID = testChannelId) = ChatChannel(
        id = id,
        name = "test-channel",
        type = ChatChannelType.PUBLIC,
    )

    @Test
    fun `chatMessage with missing channel throws`() = runTest {
        coEvery { chatService.getById(testChannelId) } returns null

        val flow = controller.chatMessage(authContext, testChannelId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `chatMessage with valid channel emits events`() = runTest {
        val channel = testChannel()
        val message = mockk<ChatMessage>()
        coEvery { chatService.getById(testChannelId) } returns channel
        every { chatService.subscribe(testChannelId, profile.id) } returns flowOf(message)

        val results = controller.chatMessage(authContext, testChannelId).toList()

        assertEquals(1, results.size)
        assertEquals(testChannelId, results[0].channelId)
        assertEquals(message, results[0].message)
        coVerify(exactly = 1) { profileService.getPrimaryProfile(any()) }
        coVerify(exactly = 1) { chatService.subscribe(testChannelId, profile.id) }
    }

    @Test
    fun `onUserTyping with missing channel throws`() = runTest {
        coEvery { chatService.getById(testChannelId) } returns null

        val flow = controller.onUserTyping(authContext, testChannelId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `onUserTyping with valid channel emits events`() = runTest {
        val channel = testChannel()
        val event = mockk<UserTypingEvent>()
        coEvery { chatService.getById(testChannelId) } returns channel
        every { chatService.subscribeTyping(testChannelId, profile.id) } returns flowOf(event)

        val results = controller.onUserTyping(authContext, testChannelId).toList()

        assertEquals(1, results.size)
        assertEquals(event, results[0])
        coVerify(exactly = 1) { chatService.subscribeTyping(testChannelId, profile.id) }
    }

    @Test
    fun `onPresenceUpdate with missing channel throws`() = runTest {
        coEvery { chatService.getById(testChannelId) } returns null

        val flow = controller.onPresenceUpdate(authContext, testChannelId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `onPresenceUpdate with valid channel emits events`() = runTest {
        val channel = testChannel()
        val event = mockk<PresenceUpdateEvent>()
        coEvery { chatService.getById(testChannelId) } returns channel
        every { chatService.subscribePresence(testChannelId, profile.id) } returns flowOf(event)

        val results = controller.onPresenceUpdate(authContext, testChannelId).toList()

        assertEquals(1, results.size)
        assertEquals(event, results[0])
        coVerify(exactly = 1) { chatService.subscribePresence(testChannelId, profile.id) }
    }

    @Test
    fun `onReaction with missing channel throws`() = runTest {
        coEvery { chatService.getById(testChannelId) } returns null

        val flow = controller.onReaction(authContext, testChannelId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `onReaction with valid channel emits events`() = runTest {
        val channel = testChannel()
        val event = mockk<MessageReactionEvent>()
        coEvery { chatService.getById(testChannelId) } returns channel
        every { chatService.subscribeReactions(testChannelId, profile.id) } returns flowOf(event)

        val results = controller.onReaction(authContext, testChannelId).toList()

        assertEquals(1, results.size)
        assertEquals(event, results[0])
        coVerify(exactly = 1) { chatService.subscribeReactions(testChannelId, profile.id) }
    }
}
