@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server.installer

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessage
import bosca.chat.service.ChatChannelInvitationService
import bosca.chat.service.ChatService
import bosca.collaboration.events.ChatMentionEvent
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.events.PrayerReactionType
import bosca.community.model.Prayer
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerStatus
import bosca.community.service.PrayerService
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageOutboxService
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.requireCompleted
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Executes every installed social notification graph through the real pipeline engine. */
class DefaultSocialNotificationPipelineExecutionTest {

    private val chatService = mockk<ChatService>()
    private val invitationService = mockk<ChatChannelInvitationService>()
    private val profileService = mockk<ProfileService>()
    private val metadataService = mockk<MetadataService>()
    private val requestService = mockk<ProfileRelationshipRequestService>()
    private val relationshipService = mockk<ProfileRelationshipService>()
    private val prayerService = mockk<PrayerService>()
    private val outbox = mockk<MessageOutboxService>()
    private val messages = mutableListOf<Message>()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        messages.clear()
        coEvery { outbox.enqueueOnce(any(), capture(messages)) } returns Unit
        provides<ChatService> { chatService }
        provides<ChatChannelInvitationService> { invitationService }
        provides<ProfileService> { profileService }
        provides<MetadataService> { metadataService }
        provides<ProfileRelationshipRequestService> { requestService }
        provides<ProfileRelationshipService> { relationshipService }
        provides<PrayerService> { prayerService }
        provides<MessageOutboxService> { outbox }
        provides<SocialNotificationConfiguration> { SocialNotificationConfiguration("https://studio.example") }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `every social notification pipeline reaches its typed action node`() = runTest {
        val messageChannel = UUID.random()
        val reactionChannel = UUID.random()
        val invitationChannel = UUID.random()
        val joinedChannel = UUID.random()
        val sender = UUID.random()
        val messageRecipient = UUID.random()
        val reactor = UUID.random()
        val messageAuthor = UUID.random()
        val inviter = UUID.random()
        val invitee = UUID.random()
        val joined = UUID.random()
        val existingMember = UUID.random()
        val requestId = UUID.random()
        val requester = UUID.random()
        val requestTarget = UUID.random()
        val approvalId = UUID.random()
        val approvalRecipient = UUID.random()
        val relatedProfile = UUID.random()
        val mentionRecipient = UUID.random()
        val invitationId = UUID.random()
        val prayerId = UUID.random()
        val prayerOwner = UUID.random()
        val prayerReactor = UUID.random()
        val prayerCommenter = UUID.random()

        val names = mapOf(
            sender to "Sender",
            messageRecipient to "Message Recipient",
            reactor to "Reactor",
            messageAuthor to "Message Author",
            inviter to "Inviter",
            invitee to "Invitee",
            joined to "New Member",
            existingMember to "Existing Member",
            requester to "Requester",
            requestTarget to "Request Target",
            approvalRecipient to "Approval Recipient",
            relatedProfile to "Related Profile",
            mentionRecipient to "Mention Recipient",
            prayerOwner to "Prayer Owner",
            prayerReactor to "Prayer Reactor",
            prayerCommenter to "Prayer Commenter",
        )
        coEvery { profileService.getById(any()) } answers { profile(firstArg(), names[firstArg()] ?: "Profile") }
        coEvery { profileService.getAllByIds(any()) } answers {
            firstArg<List<UUID>>().map { profile(it, names[it] ?: "Profile") }
        }
        coEvery { chatService.getById(messageChannel) } returns channel(messageChannel, "Messages")
        coEvery { chatService.getById(invitationChannel) } returns channel(invitationChannel, "Invitations")
        coEvery { chatService.getById(joinedChannel) } returns channel(joinedChannel, "Joined")
        coEvery { chatService.getMembers(messageChannel, 0, any()) } returns listOf(
            member(messageChannel, sender),
            member(messageChannel, messageRecipient),
        )
        coEvery { chatService.getMessage(reactionChannel, 7) } returns ChatMessage(
            sequence = 7,
            timestamp = OffsetDateTime.now(),
            senderId = messageAuthor,
            content = listOf(MessageContent(MessageContentType.TEXT, "react to me")),
        )
        coEvery { chatService.getMember(reactionChannel, messageAuthor) } returns
            member(reactionChannel, messageAuthor)
        coEvery { chatService.canParticipate(messageAuthor) } returns true
        coEvery { chatService.canParticipate(reactor) } returns true
        coEvery { chatService.getMembers(joinedChannel, 0, any()) } returns listOf(
            member(joinedChannel, joined),
            member(joinedChannel, existingMember),
        )
        coEvery { chatService.getMember(joinedChannel, joined) } returns member(joinedChannel, joined)
        coEvery { chatService.canParticipate(invitee) } returns true
        coEvery { chatService.canParticipate(joined) } returns true
        coEvery { invitationService.getById(invitationId) } returns ChatChannelInvitation(
            id = invitationId,
            channelId = invitationChannel,
            inviterProfileId = inviter,
            inviteeProfileId = invitee,
            role = "member",
            status = ChatChannelInvitationStatus.PENDING,
            version = 0,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )
        coEvery { requestService.getById(requestId) } returns ProfileRelationshipRequest(
            id = requestId,
            requesterProfileId = requester,
            targetProfileId = requestTarget,
            type = "friend",
            status = ProfileRelationshipRequestStatus.PENDING,
            version = 0,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )
        coEvery {
            relationshipService.getRelationship(approvalRecipient, relatedProfile, "friend")
        } returns ProfileRelationship(approvalRecipient, relatedProfile, "friend")
        coEvery { prayerService.getRequest(prayerId) } returns prayer(prayerId, prayerOwner)
        coEvery { prayerService.hasLiked(prayerId, prayerReactor) } returns true
        coEvery { prayerService.getComment(42L) } returns prayerComment(42L, prayerId, prayerCommenter)

        val cases = listOf(
            Case(
                DefaultSocialNotificationPipelinesInstaller.CHAT_MESSAGE_PIPELINE,
                PipelineValue.of(
                    ChatMessageSentEvent(
                        messageChannel,
                        sender,
                        1,
                        listOf(MessageContent(MessageContentType.TEXT, "hello")),
                        OffsetDateTime.now(),
                    ),
                    ChatMessageSentEvent.serializer(),
                ),
                "chat-message",
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.CHAT_REACTION_PIPELINE,
                PipelineValue.of(
                    ChatMessageReactionAddedEvent(
                        reactionId = UUID.random(),
                        channelId = reactionChannel,
                        sequence = 7,
                        reactorId = reactor,
                        messageAuthorId = messageAuthor,
                        emoji = "👍",
                    ),
                    ChatMessageReactionAddedEvent.serializer(),
                ),
                "chat-reaction",
                listOf(MessageChannel.PUSH),
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.PRAYER_REACTION_PIPELINE,
                PipelineValue.of(
                    PrayerReactionAddedEvent(
                        activityId = UUID.random(),
                        prayerId = prayerId,
                        reactorId = prayerReactor,
                        reaction = PrayerReactionType.LIKED,
                    ),
                    PrayerReactionAddedEvent.serializer(),
                ),
                "prayer-reaction",
                listOf(MessageChannel.PUSH),
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.PRAYER_COMMENT_PIPELINE,
                PipelineValue.of(
                    PrayerCommentAddedEvent(
                        commentId = 42L,
                        prayerId = prayerId,
                        commenterId = prayerCommenter,
                    ),
                    PrayerCommentAddedEvent.serializer(),
                ),
                "prayer-comment",
                listOf(MessageChannel.PUSH),
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.CHANNEL_INVITATION_PIPELINE,
                PipelineValue.of(
                    ChatChannelInvitationSentEvent(invitationId, invitationChannel, inviter, invitee, "member"),
                    ChatChannelInvitationSentEvent.serializer(),
                ),
                "channel-invitation",
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.CHANNEL_JOINED_PIPELINE,
                PipelineValue.of(
                    ChatChannelJoinedEvent(UUID.random(), joinedChannel, joined, "member"),
                    ChatChannelJoinedEvent.serializer(),
                ),
                "channel-joined",
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.RELATIONSHIP_REQUESTED_PIPELINE,
                PipelineValue.of(
                    ProfileRelationshipRequested(requestId, requester, requestTarget, "friend"),
                    ProfileRelationshipRequested.serializer(),
                ),
                "relationship-request",
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.RELATIONSHIP_APPROVED_PIPELINE,
                PipelineValue.of(
                    ProfileRelationshipRequestApproved(approvalId, approvalRecipient, relatedProfile, "friend"),
                    ProfileRelationshipRequestApproved.serializer(),
                ),
                "relationship-added",
            ),
            Case(
                DefaultSocialNotificationPipelinesInstaller.CHAT_MENTION_PIPELINE,
                PipelineValue.of(
                    ChatMentionEvent(
                        messageChannel,
                        sender,
                        2,
                        listOf(mentionRecipient),
                        "Sender",
                        "Messages",
                        listOf(MessageContent(MessageContentType.TEXT, "@mention hello")),
                    ),
                    ChatMentionEvent.serializer(),
                ),
                null,
            ),
        )

        val pipelines = pipelines()
        for ((index, case) in cases.withIndex()) {
            execute(pipelines.getValue(case.pipelineName), case.input)

            assertEquals(index + 1, messages.size, case.pipelineName)
            assertEquals(case.channels, messages.last().channels)
            assertEquals(case.template, messages.last().bmlTemplate?.templateKey)
        }
    }

    private suspend fun pipelines(): Map<String, Pipeline> {
        val captured = mutableListOf<Pipeline>()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.getAll() } returns emptyList()
        coEvery { pipelineService.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        coEvery {
            pipelineService.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)
        DefaultSocialNotificationPipelinesInstaller(pipelineService)
            .install(mockk(relaxed = true), mockk(relaxed = true))
        return captured.associateBy { it.name }
    }

    private suspend fun execute(pipeline: Pipeline, input: PipelineValue) {
        PipelineExecutorImpl().execute(
            pipeline,
            input,
            PipelineContext(AuthenticationContext(null, null), json),
        ).requireCompleted()
    }

    private fun channel(id: UUID, name: String) = ChatChannel(
        id = id,
        name = name,
        type = ChatChannelType.GROUP,
    )

    private fun member(channelId: UUID, profileId: UUID) = ChatChannelMember(channelId, profileId, "member")

    private fun profile(id: UUID, name: String) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
    )

    private fun prayer(id: UUID, ownerId: UUID) = Prayer(
        id = id,
        profileId = ownerId,
        title = "Prayer",
        content = JsonPrimitive("content"),
        status = PrayerStatus.ACTIVE,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        lastActivityAt = OffsetDateTime.now(),
        attributes = null,
    )

    private fun prayerComment(id: Long, prayerId: UUID, profileId: UUID) = PrayerComment(
        id = id,
        prayerId = prayerId,
        profileId = profileId,
        content = "Comment",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private data class Case(
        val pipelineName: String,
        val input: PipelineValue,
        val template: String?,
        val channels: List<MessageChannel> = listOf(MessageChannel.EMAIL, MessageChannel.PUSH),
    )
}
