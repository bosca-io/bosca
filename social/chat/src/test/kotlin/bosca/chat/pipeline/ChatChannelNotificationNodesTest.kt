package bosca.chat.pipeline

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.service.ChatChannelInvitationService
import bosca.chat.service.ChatService
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageOutboxService
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatChannelNotificationNodesTest {

    private val chatService = mockk<ChatService>()
    private val profileService = mockk<ProfileService>()
    private val invitationService = mockk<ChatChannelInvitationService>()
    private val messageOutboxService = mockk<MessageOutboxService>()
    private val messages = mutableListOf<Message>()
    private val sourceIds = mutableListOf<UUID>()
    private val configuration = SocialNotificationConfiguration("https://app.example")

    @BeforeTest
    fun setUp() {
        messages.clear()
        sourceIds.clear()
        coEvery { messageOutboxService.enqueueOnce(capture(sourceIds), capture(messages)) } returns Unit
    }

    @Test
    fun `channel invitation notifies the invitee through configured channels`() = runBlocking {
        val channelId = UUID.random()
        val inviterId = UUID.random()
        val inviteeId = UUID.random()
        val invitationId = UUID.random()
        coEvery { invitationService.getById(invitationId) } returns invitation(invitationId, channelId, inviterId, inviteeId)
        coEvery { chatService.canParticipate(inviteeId) } returns true
        coEvery { chatService.getById(channelId) } returns channel(channelId)
        coEvery { profileService.getById(inviterId) } returns profile(inviterId, "Maya")
        val event = ChatChannelInvitationSentEvent(invitationId, channelId, inviterId, inviteeId, "member")
        val node = ChatChannelInvitationNotificationNode("send")

        node.deliver(event, invitationService, chatService, profileService, configuration, messageOutboxService)
        node.deliver(event, invitationService, chatService, profileService, configuration, messageOutboxService)

        val message = messages.first()
        assertEquals(listOf(inviteeId), message.recipients)
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), message.channels)
        assertEquals(NotificationTypeKeys.SOCIAL_ACTIVITY, message.type)
        assertEquals("channel-invitation", message.bmlTemplate?.templateKey)
        assertNull(message.pushOptions?.defaultAction?.label)
        assertEquals("view-invitation", message.pushOptions?.defaultAction?.id)
        assertEquals("chat-invitation-$invitationId", message.pushOptions?.threadId)
        assertEquals(sourceIds.first(), sourceIds.last(), "pipeline retries must reuse the outbox source ID")
    }

    @Test
    fun `channel invitation observes current state and channel selection`() = runBlocking {
        val channelId = UUID.random()
        val inviterId = UUID.random()
        val inviteeId = UUID.random()
        val invitationId = UUID.random()
        val event = ChatChannelInvitationSentEvent(invitationId, channelId, inviterId, inviteeId, "member")
        coEvery { invitationService.getById(invitationId) } returns invitation(
            invitationId,
            channelId,
            inviterId,
            inviteeId,
            ChatChannelInvitationStatus.CANCELLED,
        )

        ChatChannelInvitationNotificationNode("send").deliver(
            event,
            invitationService,
            chatService,
            profileService,
            configuration,
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
        coEvery { invitationService.getById(invitationId) } returns invitation(invitationId, channelId, inviterId, inviteeId)
        coEvery { chatService.canParticipate(inviteeId) } returns true
        coEvery { chatService.getById(channelId) } returns channel(channelId)
        coEvery { profileService.getById(inviterId) } returns profile(inviterId, "Maya")

        ChatChannelInvitationNotificationNode("send", email = false).deliver(
            event,
            invitationService,
            chatService,
            profileService,
            configuration,
            messageOutboxService,
        )

        assertEquals(listOf(MessageChannel.PUSH), messages.single().channels)
    }

    @Test
    fun `channel invitation skips delivery when invitee loses messaging eligibility`() = runBlocking {
        val channelId = UUID.random()
        val inviterId = UUID.random()
        val inviteeId = UUID.random()
        val invitationId = UUID.random()
        coEvery { invitationService.getById(invitationId) } returns invitation(invitationId, channelId, inviterId, inviteeId)
        coEvery { chatService.canParticipate(inviteeId) } returns false

        ChatChannelInvitationNotificationNode("send").deliver(
            ChatChannelInvitationSentEvent(invitationId, channelId, inviterId, inviteeId, "member"),
            invitationService,
            chatService,
            profileService,
            configuration,
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
        coVerify(exactly = 0) { chatService.getById(any()) }
    }

    @Test
    fun `channel joined notifies every other active current member`() = runBlocking {
        val channelId = UUID.random()
        val joinedId = UUID.random()
        val member1 = UUID.random()
        val member2 = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId)
        coEvery { chatService.getMember(channelId, joinedId) } returns ChatChannelMember(channelId, joinedId, "member")
        coEvery { chatService.canParticipate(joinedId) } returns true
        coEvery { chatService.getMembers(channelId, 0, ChatChannelJoinedNotificationNode.MEMBER_BATCH_SIZE) } returns listOf(
            ChatChannelMember(channelId, joinedId, "member"),
            ChatChannelMember(channelId, member1, "member"),
            ChatChannelMember(channelId, member2, "admin"),
        )
        coEvery { profileService.getAllByIds(listOf(member1, member2)) } returns listOf(
            profile(member1, "Member 1"),
            profile(member2, "Member 2", deleted = true),
        )
        coEvery { profileService.getById(joinedId) } returns profile(joinedId, "Jordan")
        val event = ChatChannelJoinedEvent(UUID.random(), channelId, joinedId, "member")
        val node = ChatChannelJoinedNotificationNode("send")

        node.deliver(event, chatService, profileService, configuration, messageOutboxService)

        val message = messages.single()
        assertEquals(listOf(member1), message.recipients)
        assertEquals("channel-joined", message.bmlTemplate?.templateKey)
        assertEquals("open-chat", message.pushOptions?.defaultAction?.id)
        assertEquals("chat-$channelId", message.pushOptions?.threadId)
        assertEquals(node.notificationId(event.joinId), sourceIds.single())
    }

    @Test
    fun `channel joined skips delivery after profile leaves`() = runBlocking {
        val channelId = UUID.random()
        val joinedId = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId)
        coEvery { chatService.getMember(channelId, joinedId) } returns null

        ChatChannelJoinedNotificationNode("send").deliver(
            ChatChannelJoinedEvent(UUID.random(), channelId, joinedId, "member"),
            chatService,
            profileService,
            configuration,
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
        coVerify(exactly = 0) { chatService.getMembers(any(), any(), any()) }
    }

    @Test
    fun `channel joined fanout pages locally`() = runBlocking {
        val channelId = UUID.random()
        val joinedId = UUID.random()
        val firstPage = List(ChatChannelJoinedNotificationNode.MEMBER_BATCH_SIZE) {
            ChatChannelMember(channelId, UUID.random(), "member")
        }
        val finalMember = ChatChannelMember(channelId, UUID.random(), "member")
        coEvery { chatService.getById(channelId) } returns channel(channelId)
        coEvery { chatService.getMember(channelId, joinedId) } returns ChatChannelMember(channelId, joinedId, "member")
        coEvery { chatService.canParticipate(joinedId) } returns true
        coEvery { chatService.getMembers(channelId, 0, ChatChannelJoinedNotificationNode.MEMBER_BATCH_SIZE) } returns firstPage
        coEvery {
            chatService.getMembers(
                channelId,
                ChatChannelJoinedNotificationNode.MEMBER_BATCH_SIZE.toLong(),
                ChatChannelJoinedNotificationNode.MEMBER_BATCH_SIZE,
            )
        } returns listOf(finalMember)
        coEvery { profileService.getAllByIds(firstPage.map { it.profileId }) } returns
            firstPage.map { profile(it.profileId, "Member") }
        coEvery { profileService.getAllByIds(listOf(finalMember.profileId)) } returns
            listOf(profile(finalMember.profileId, "Final member"))
        coEvery { profileService.getById(joinedId) } returns profile(joinedId, "Jordan")

        ChatChannelJoinedNotificationNode("send").deliver(
            ChatChannelJoinedEvent(UUID.random(), channelId, joinedId, "member"),
            chatService,
            profileService,
            configuration,
            messageOutboxService,
        )

        assertEquals(firstPage.map { it.profileId } + finalMember.profileId, messages.single().recipients)
    }

    private fun channel(id: UUID) = ChatChannel(id = id, name = "Planning", type = ChatChannelType.GROUP)

    private fun invitation(
        id: UUID,
        channelId: UUID,
        inviterId: UUID,
        inviteeId: UUID,
        status: ChatChannelInvitationStatus = ChatChannelInvitationStatus.PENDING,
    ) = ChatChannelInvitation(
        id = id,
        channelId = channelId,
        inviterProfileId = inviterId,
        inviteeProfileId = inviteeId,
        role = "member",
        status = status,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun profile(id: UUID, name: String, deleted: Boolean = false) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
        deletedAt = OffsetDateTime.now().takeIf { deleted },
    )
}
