package bosca.collaboration.pipeline

import bosca.collaboration.events.ChatMentionEvent
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageOutboxService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMentionNotificationNodeTest {

    private val node = ChatMentionNotificationNode("send")

    @Test
    fun `queues one retry-safe message for configured channels`() = runBlocking {
        val recipient = UUID.random()
        val event = event(listOf(recipient))
        val outbox = mockk<MessageOutboxService>()
        val messages = mutableListOf<Message>()
        val sourceIds = mutableListOf<UUID>()
        coEvery { outbox.enqueueOnce(capture(sourceIds), capture(messages)) } returns Unit

        node.deliver(event, outbox)
        node.deliver(event, outbox)

        assertEquals(sourceIds.first(), sourceIds.last())
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), messages.first().channels)
        assertEquals(listOf(recipient), messages.first().recipients)
        assertEquals("Avery mentioned you in Planning", messages.first().subject)
        assertEquals("hello", messages.first().content.single().content)
    }

    @Test
    fun `supports push without email and skips an empty audience`() = runBlocking {
        val outbox = mockk<MessageOutboxService>()
        val messages = mutableListOf<Message>()
        coEvery { outbox.enqueueOnce(any(), capture(messages)) } returns Unit

        ChatMentionNotificationNode("send", email = false).deliver(event(listOf(UUID.random())), outbox)
        ChatMentionNotificationNode("send").deliver(event(emptyList()), outbox)

        assertEquals(listOf(MessageChannel.PUSH), messages.single().channels)
    }

    @Test
    fun `renderPreview picks the first TEXT block`() {
        val preview = node.renderPreview(
            listOf(
                MessageContent(MessageContentType.IMAGE, "image-url"),
                MessageContent(MessageContentType.TEXT, "hello world"),
                MessageContent(MessageContentType.TEXT, "ignored"),
            ),
        )
        assertEquals("hello world", preview)
    }

    @Test
    fun `renderPreview falls back to media-message placeholder when no TEXT`() {
        assertEquals(
            "(media message)",
            node.renderPreview(listOf(MessageContent(MessageContentType.IMAGE, "image-url"))),
        )
        assertEquals("(media message)", node.renderPreview(emptyList()))
    }

    @Test
    fun `renderPreview trims and truncates content`() {
        assertEquals(
            "hello",
            node.renderPreview(listOf(MessageContent(MessageContentType.TEXT, "   hello\n\n"))),
        )
        val preview = node.renderPreview(
            listOf(MessageContent(MessageContentType.TEXT, "x".repeat(ChatMentionNotificationNode.PREVIEW_LIMIT + 50))),
        )
        assertEquals(ChatMentionNotificationNode.PREVIEW_LIMIT, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    private fun event(recipients: List<UUID>) = ChatMentionEvent(
        channelId = UUID.random(),
        senderId = UUID.random(),
        sequence = 9,
        recipientProfileIds = recipients,
        senderName = "Avery",
        channelName = "Planning",
        content = listOf(MessageContent(MessageContentType.TEXT, "hello")),
    )
}
