package bosca.chat.pipeline

import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.service.ChatService
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageOutboxService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
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
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatMessageNotificationNodeTest {

    @Test
    fun `notifies active non-sender members regardless of connected presence`() = runBlocking {
        val senderId = UUID.random()
        val onlineId = UUID.random()
        val offlineId = UUID.random()
        val unknownId = UUID.random()
        val deletedId = UUID.random()
        val channelId = UUID.random()
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val metadataService = mockk<MetadataService>()
        val captured = slot<Message>()
        val sourceId = slot<UUID>()
        val nonImageId = UUID.random()
        val imageId = UUID.random()
        coEvery { chatService.getById(channelId) } returns ChatChannel(
            id = channelId,
            name = "Planning",
            type = ChatChannelType.GROUP,
        )
        coEvery {
            chatService.getMembers(channelId, 0, ChatMessageNotificationNode.MEMBER_BATCH_SIZE)
        } returns listOf(
            member(channelId, senderId),
            member(channelId, onlineId),
            member(channelId, offlineId),
            member(channelId, unknownId),
            member(channelId, deletedId),
        )
        coEvery { profileService.getAllByIds(listOf(onlineId, offlineId, unknownId, deletedId)) } returns listOf(
            profile(onlineId),
            profile(offlineId),
            profile(unknownId),
            profile(deletedId, deleted = true),
        )
        coEvery { profileService.getById(senderId) } returns profile(senderId, "Avery")
        coEvery { metadataService.getById(nonImageId) } returns Metadata(
            id = nonImageId,
            name = "Release notes",
            type = MetadataType.STANDARD,
            contentType = "application/pdf",
            contentLength = 128,
            languageTag = "en",
            workflowStateId = "published",
            publicContent = true,
        )
        coEvery { metadataService.getById(imageId) } returns Metadata(
            id = imageId,
            name = "Project image",
            type = MetadataType.STANDARD,
            contentType = "image/jpeg",
            contentLength = 128,
            languageTag = "en",
            workflowStateId = "published",
            publicContent = true,
            attributes = buildJsonObject {
                put("webp", buildJsonObject { put("small", buildJsonObject { put("invalid", true) }) })
                put("jpeg", buildJsonObject { put("medium", "thumb-original-50.0-jpeg") })
            },
        )
        coEvery { outbox.enqueueOnce(capture(sourceId), capture(captured)) } returns Unit
        val fixture = fixture(chatService, profileService, metadataService, outbox)
        val event = event(
            channelId = channelId,
            senderId = senderId,
            sequence = 42,
            content = listOf(
                MessageContent(MessageContentType.TEXT, "  Project update  "),
                MessageContent(MessageContentType.METADATA, nonImageId.toString()),
                MessageContent(
                    MessageContentType.METADATA,
                    """{"type":"image","id":"$imageId","name":"Project image"}""",
                ),
            ),
        )

        fixture.deliver(event)

        val message = captured.captured
        assertEquals(fixture.node.notificationId(event), sourceId.captured)
        assertEquals(listOf(onlineId, offlineId, unknownId), message.recipients)
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), message.channels)
        assertEquals(NotificationTypeKeys.SOCIAL_ACTIVITY, message.type)
        assertEquals("chat-message", message.bmlTemplate?.templateKey)
        val payload = message.bmlTemplate?.payload?.jsonObject
        assertEquals(channelId.toString(), payload?.get("channelId")?.jsonPrimitive?.content)
        assertEquals(42, payload?.get("sequence")?.jsonPrimitive?.content?.toLong())
        assertEquals(null, payload?.get("messageText"))
        assertEquals(null, payload?.get("attachmentCount"))
        assertEquals(
            "https://app.example/communications/channels?channelId=$channelId&sequence=42",
            payload?.get("actionUrl")?.jsonPrimitive?.content,
        )
        val conversation = message.pushOptions?.richContent?.conversation
        assertEquals("Planning", conversation?.title)
        assertEquals("42", conversation?.messageId)
        assertEquals("Avery", conversation?.senderName)
        assertEquals(true, conversation?.groupConversation)
        assertEquals(
            "https://app.example/content/image/$imageId.jpeg?key=thumb-original-50.0-jpeg",
            message.pushOptions?.richContent?.attachments?.single()?.url,
        )
    }

    @Test
    fun `channel settings and content safety select delivery channels`() {
        val both = ChatMessageNotificationNode("send")
        val html = listOf(MessageContent(MessageContentType.HTML, "<p>Project update</p>"))
        val text = listOf(MessageContent(MessageContentType.TEXT, "Project update"))

        assertEquals(listOf(MessageChannel.EMAIL), both.notificationChannels(html))
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), both.notificationChannels(text))
        assertEquals(listOf(MessageChannel.PUSH), ChatMessageNotificationNode("send", email = false).notificationChannels(text))
        assertEquals(listOf(MessageChannel.EMAIL), ChatMessageNotificationNode("send", push = false).notificationChannels(text))
        assertEquals(emptyList(), ChatMessageNotificationNode("send", email = false, push = false).notificationChannels(text))
        assertNull(both.messageText(listOf(MessageContent(MessageContentType.IMAGE, "image-id"))))
        assertEquals(1, both.attachmentCount(listOf(MessageContent(MessageContentType.IMAGE, "image-id"))))
        assertEquals(0, both.attachmentCount(listOf(MessageContent(MessageContentType.MENTION, "profile-id"))))
    }

    @Test
    fun `fanout pages locally and enqueues one logical notification`() = runBlocking {
        val senderId = UUID.random()
        val channelId = UUID.random()
        val firstPage = List(ChatMessageNotificationNode.MEMBER_BATCH_SIZE) { member(channelId, UUID.random()) }
        val finalRecipient = UUID.random()
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val captured = slot<Message>()
        coEvery { chatService.getById(channelId) } returns ChatChannel(
            id = channelId,
            name = "Planning",
            type = ChatChannelType.GROUP,
        )
        coEvery { chatService.getMembers(channelId, 0, ChatMessageNotificationNode.MEMBER_BATCH_SIZE) } returns firstPage
        coEvery {
            chatService.getMembers(
                channelId,
                ChatMessageNotificationNode.MEMBER_BATCH_SIZE.toLong(),
                ChatMessageNotificationNode.MEMBER_BATCH_SIZE,
            )
        } returns listOf(member(channelId, finalRecipient))
        coEvery { profileService.getAllByIds(firstPage.map { it.profileId }) } returns
            firstPage.map { profile(it.profileId) }
        coEvery { profileService.getAllByIds(listOf(finalRecipient)) } returns listOf(profile(finalRecipient))
        coEvery { profileService.getById(senderId) } returns profile(senderId, "Avery")
        coEvery { outbox.enqueueOnce(any(), capture(captured)) } returns Unit
        val fixture = fixture(chatService, profileService, messageOutboxService = outbox)
        val event = event(channelId, senderId, 42, listOf(MessageContent(MessageContentType.TEXT, "hello")))

        fixture.deliver(event)

        coVerify(exactly = 1) { outbox.enqueueOnce(fixture.node.notificationId(event), any()) }
        assertEquals(firstPage.map { it.profileId } + finalRecipient, captured.captured.recipients)
    }

    @Test
    fun `direct notification hides the internal channel name`() = runBlocking {
        val channelId = UUID.random()
        val senderId = UUID.random()
        val recipientId = UUID.random()
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val captured = slot<Message>()
        coEvery { chatService.getById(channelId) } returns ChatChannel(
            id = channelId,
            name = "DM",
            type = ChatChannelType.DIRECT,
        )
        coEvery { chatService.getMembers(channelId, 0, ChatMessageNotificationNode.MEMBER_BATCH_SIZE) } returns listOf(
            member(channelId, senderId),
            member(channelId, recipientId),
        )
        coEvery { profileService.getAllByIds(listOf(recipientId)) } returns listOf(profile(recipientId))
        coEvery { profileService.getById(senderId) } returns profile(senderId, "Avery")
        coEvery { outbox.enqueueOnce(any(), capture(captured)) } returns Unit
        val fixture = fixture(chatService, profileService, messageOutboxService = outbox)

        fixture.deliver(event(channelId, senderId, 9, listOf(MessageContent(MessageContentType.TEXT, "Hello"))))

        val payload = captured.captured.bmlTemplate?.payload?.jsonObject
        assertEquals("true", payload?.get("direct")?.jsonPrimitive?.content)
        assertEquals(null, payload?.get("channelName"))
        assertEquals("Avery", captured.captured.pushOptions?.richContent?.conversation?.title)
        assertEquals(false, captured.captured.pushOptions?.richContent?.conversation?.groupConversation)
    }

    private fun event(
        channelId: UUID,
        senderId: UUID,
        sequence: Long,
        content: List<MessageContent>,
    ) = ChatMessageSentEvent(channelId, senderId, sequence, content, OffsetDateTime.now())

    private fun member(channelId: UUID, profileId: UUID) = ChatChannelMember(channelId, profileId, "member")

    private fun profile(id: UUID, name: String = "Profile", deleted: Boolean = false) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
        deletedAt = OffsetDateTime.now().takeIf { deleted },
    )

    private fun fixture(
        chatService: ChatService = mockk(relaxed = true),
        profileService: ProfileService = mockk(relaxed = true),
        metadataService: MetadataService = mockk(relaxed = true),
        messageOutboxService: MessageOutboxService = mockk(relaxed = true),
    ) = Fixture(
        ChatMessageNotificationNode("send"),
        chatService,
        profileService,
        metadataService,
        messageOutboxService,
    )

    private data class Fixture(
        val node: ChatMessageNotificationNode,
        val chatService: ChatService,
        val profileService: ProfileService,
        val metadataService: MetadataService,
        val messageOutboxService: MessageOutboxService,
    ) {
        suspend fun deliver(event: ChatMessageSentEvent) = node.deliver(
            event,
            chatService,
            profileService,
            metadataService,
            messageOutboxService,
            SocialNotificationConfiguration("https://app.example"),
        )
    }
}
