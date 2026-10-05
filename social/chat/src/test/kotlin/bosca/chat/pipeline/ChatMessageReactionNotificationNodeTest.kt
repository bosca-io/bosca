package bosca.chat.pipeline

import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatMessage
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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatMessageReactionNotificationNodeTest {

    @Test
    fun `queues push for the active message author`() = runTest {
        val event = event()
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val capturedId = slot<UUID>()
        val captured = slot<Message>()
        coEvery { chatService.getMessage(event.channelId, event.sequence) } returns message(event)
        coEvery { chatService.getMember(event.channelId, event.messageAuthorId) } returns
            ChatChannelMember(event.channelId, event.messageAuthorId, "member")
        coEvery { chatService.canParticipate(event.messageAuthorId) } returns true
        coEvery { chatService.canParticipate(event.reactorId) } returns true
        coEvery { profileService.getById(event.reactorId) } returns profile(event.reactorId, "Avery")
        coEvery { outbox.enqueueOnce(capture(capturedId), capture(captured)) } returns Unit
        val node = ChatMessageReactionNotificationNode("send")

        node.deliver(
            event,
            chatService,
            profileService,
            SocialNotificationConfiguration("https://app.example"),
            outbox,
        )

        val notification = captured.captured
        assertEquals(node.notificationId(event), capturedId.captured)
        assertEquals(listOf(MessageChannel.PUSH), notification.channels)
        assertEquals(listOf(event.messageAuthorId), notification.recipients)
        assertEquals(event.reactorId, notification.sender)
        assertEquals("", notification.subject)
        assertEquals(emptyList(), notification.content)
        assertEquals(NotificationTypeKeys.SOCIAL_ACTIVITY, notification.type)
        assertEquals("bosca-messages", notification.bmlTemplate?.project)
        assertEquals("chat-reaction", notification.bmlTemplate?.templateKey)
        val payload = notification.bmlTemplate?.payload?.jsonObject
        assertEquals("Avery", payload?.get("reactorName")?.jsonPrimitive?.content)
        assertEquals(event.channelId.toString(), payload?.get("channelId")?.jsonPrimitive?.content)
        assertEquals(event.sequence, payload?.get("sequence")?.jsonPrimitive?.content?.toLong())
        assertEquals(event.reactionId.toString(), payload?.get("reactionId")?.jsonPrimitive?.content)
        assertNull(payload?.get("emoji"))
        assertEquals("CHAT_MESSAGE_REACTION", notification.pushOptions?.category)
        assertEquals("chat_message_reaction", notification.pushOptions?.data?.get("event"))
        assertEquals(event.sequence.toString(), notification.pushOptions?.data?.get("sequence"))
        assertNull(notification.pushOptions?.data?.get("emoji"))
        assertEquals(
            "https://app.example/communications/channels?channelId=${event.channelId}&sequence=${event.sequence}",
            notification.pushOptions?.defaultAction?.url,
        )
    }

    @Test
    fun `does not notify for self reactions or a disabled action`() = runTest {
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val configuration = SocialNotificationConfiguration("https://app.example")
        val self = event().let { it.copy(reactorId = it.messageAuthorId) }

        ChatMessageReactionNotificationNode("send").deliver(
            self,
            chatService,
            profileService,
            configuration,
            outbox,
        )
        ChatMessageReactionNotificationNode("send", push = false).deliver(
            event(),
            chatService,
            profileService,
            configuration,
            outbox,
        )

        coVerify(exactly = 0) { outbox.enqueueOnce(any(), any()) }
    }

    @Test
    fun `does not notify when the author is no longer eligible`() = runTest {
        val event = event()
        val chatService = mockk<ChatService>()
        val outbox = mockk<MessageOutboxService>()
        coEvery { chatService.getMessage(event.channelId, event.sequence) } returns message(event)
        coEvery { chatService.getMember(event.channelId, event.messageAuthorId) } returns
            ChatChannelMember(event.channelId, event.messageAuthorId, "member")
        coEvery { chatService.canParticipate(event.messageAuthorId) } returns false

        ChatMessageReactionNotificationNode("send").deliver(
            event,
            chatService,
            mockk(),
            SocialNotificationConfiguration("https://app.example"),
            outbox,
        )

        coVerify(exactly = 0) { outbox.enqueueOnce(any(), any()) }
    }

    private fun event() = ChatMessageReactionAddedEvent(
        reactionId = UUID.random(),
        channelId = UUID.random(),
        sequence = 42,
        reactorId = UUID.random(),
        messageAuthorId = UUID.random(),
        emoji = "👍",
    )

    private fun message(event: ChatMessageReactionAddedEvent) = ChatMessage(
        sequence = event.sequence,
        timestamp = OffsetDateTime.now(),
        senderId = event.messageAuthorId,
        content = emptyList(),
    )

    private fun profile(id: UUID, name: String) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
    )
}
