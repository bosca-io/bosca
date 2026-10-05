@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.community.service

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessageSendResult
import bosca.chat.service.ChatService
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.community.model.CommunityGroupMember
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.provides
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommunityChatServiceImplTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val communityService = mockk<CommunityService>()
    private lateinit var jobQueue: JobQueue
    private val service = CommunityChatServiceImpl(
        chatService,
        communityService.asProvider(),
        mockk<ProfileRelationshipService>(),
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        jobQueue = mockk(relaxed = true)
        provides<JobQueue>(name = "communityJobQueue") { jobQueue }
        provides<PubSubService> { mockk(relaxed = true) }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `creator becomes administrator and eligible community members join the channel`() = runTest {
        val groupId = UUID.random()
        val administratorProfileId = UUID.random()
        val memberProfileId = UUID.random()
        val ineligibleProfileId = UUID.random()
        val channel = ChatChannel(UUID.random(), groupId, "General", ChatChannelType.GROUP)
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        coEvery {
            chatService.createChannel(
                groupId,
                "General",
                ChatChannelType.GROUP,
                null,
                dispatchCreatedEvent = false,
                initialMemberProfileId = administratorProfileId,
                initialMemberRole = ChatChannelRoles.ADMIN,
            )
        } returns channel
        coEvery { communityService.getMembers(groupId) } returns listOf(
            CommunityGroupMember(groupId, administratorProfileId),
            CommunityGroupMember(groupId, memberProfileId),
            CommunityGroupMember(groupId, ineligibleProfileId),
        )
        coEvery { chatService.canParticipate(memberProfileId) } returns true
        coEvery { chatService.canParticipate(ineligibleProfileId) } returns false

        val result = withContext(connectionManager.asCoroutineContext()) {
            service.createGroupChannel(
                groupId,
                "General",
                ChatChannelType.GROUP,
                null,
                administratorProfileId,
            )
        }

        assertEquals(channel, result)
        coVerify {
            chatService.joinChannel(
                channel.id,
                memberProfileId,
                ChatChannelRoles.MEMBER,
                notifyExistingMembers = false,
            )
        }
        coVerify(exactly = 0) { chatService.joinChannel(channel.id, administratorProfileId, any(), any()) }
        coVerify(exactly = 0) { chatService.joinChannel(channel.id, ineligibleProfileId, any(), any()) }
        coVerify { connectionManager.beginTransaction() }
        coVerify { connectionManager.commitTransaction() }
        coVerify(exactly = 0) { connectionManager.rollbackTransaction() }
    }

    @Test
    fun `member initialization failure rolls back channel provisioning`() = runTest {
        val groupId = UUID.random()
        val administratorProfileId = UUID.random()
        val memberProfileId = UUID.random()
        val channel = ChatChannel(UUID.random(), groupId, "General", ChatChannelType.GROUP)
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        coEvery { chatService.createChannel(any(), any(), any(), any(), any(), any(), any()) } returns channel
        coEvery { communityService.getMembers(groupId) } returns listOf(
            CommunityGroupMember(groupId, memberProfileId),
        )
        coEvery { chatService.canParticipate(memberProfileId) } returns true
        coEvery {
            chatService.joinChannel(channel.id, memberProfileId, ChatChannelRoles.MEMBER, false)
        } throws IllegalStateException("membership failed")

        assertFailsWith<IllegalStateException> {
            withContext(connectionManager.asCoroutineContext()) {
                service.createGroupChannel(
                    groupId,
                    "General",
                    ChatChannelType.GROUP,
                    null,
                    administratorProfileId,
                )
            }
        }

        coVerify { connectionManager.beginTransaction() }
        coVerify(exactly = 0) { connectionManager.commitTransaction() }
        coVerify { connectionManager.rollbackTransaction() }
    }

    @Test
    fun `new group message dispatches community effects`() = runTest {
        val channelId = UUID.random()
        val senderId = UUID.random()
        val clientId = UUID.random()
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))
        coEvery {
            chatService.sendMessage(channelId, senderId, clientId, content, null)
        } returns ChatMessageSendResult(sequence = 42, duplicate = false)

        assertEquals(42, service.sendGroupMessage(channelId, senderId, clientId, content))

        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `duplicate group message does not dispatch community effects again`() = runTest {
        val channelId = UUID.random()
        val senderId = UUID.random()
        val clientId = UUID.random()
        val content = listOf(MessageContent(MessageContentType.TEXT, "hello"))
        coEvery {
            chatService.sendMessage(channelId, senderId, clientId, content, null)
        } returns ChatMessageSendResult(sequence = 42, duplicate = true)

        assertEquals(42, service.sendGroupMessage(channelId, senderId, clientId, content))

        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }
}
