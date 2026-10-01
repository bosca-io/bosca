package bosca.communications.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MessageTest {

    @Test
    fun `MessageContent stores type and content`() {
        val mc = MessageContent(type = MessageContentType.TEXT, content = "Hello world")
        assertEquals(MessageContentType.TEXT, mc.type)
        assertEquals("Hello world", mc.content)
    }

    @Test
    fun `MessageContent attributes defaults to null`() {
        val mc = MessageContent(type = MessageContentType.HTML, content = "<p>hi</p>")
        assertNull(mc.attributes)
    }

    @Test
    fun `Message stores channels, subject, recipients, and content`() {
        val recipientId = Uuid.random()
        val content = listOf(MessageContent(type = MessageContentType.TEXT, content = "Hello"))
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Test Subject",
            recipients = listOf(recipientId),
            content = content
        )
        assertEquals(listOf(MessageChannel.EMAIL), message.channels)
        assertEquals("Test Subject", message.subject)
        assertEquals(listOf(recipientId), message.recipients)
        assertEquals(content, message.content)
    }

    @Test
    fun `Message sender defaults to null`() {
        val message = Message(
            channels = listOf(MessageChannel.PUSH),
            recipients = listOf(Uuid.random()),
        )
        assertNull(message.sender)
        assertEquals("", message.subject)
        assertEquals(emptyList(), message.content)
    }

    @Test
    fun `Message pushOptions defaults to null`() {
        val message = Message(
            channels = listOf(MessageChannel.PUSH),
            subject = "Test",
            recipients = listOf(Uuid.random()),
            content = emptyList()
        )
        assertNull(message.pushOptions)
    }

    @Test
    fun `Message with sender`() {
        val senderId = Uuid.random()
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Test",
            sender = senderId,
            recipients = listOf(Uuid.random()),
            content = emptyList()
        )
        assertEquals(senderId, message.sender)
    }

    @Test
    fun `Message with multiple channels`() {
        val message = Message(
            channels = listOf(MessageChannel.EMAIL, MessageChannel.PUSH),
            subject = "Multi-channel",
            recipients = listOf(Uuid.random()),
            content = emptyList()
        )
        assertEquals(2, message.channels.size)
    }

    @Test
    fun `Message with multiple recipients`() {
        val ids = listOf(Uuid.random(), Uuid.random(), Uuid.random())
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Broadcast",
            recipients = ids,
            content = emptyList()
        )
        assertEquals(3, message.recipients.size)
    }

    @Test
    fun `Message with multiple content items`() {
        val content = listOf(
            MessageContent(type = MessageContentType.TEXT, content = "Plain text"),
            MessageContent(type = MessageContentType.HTML, content = "<b>Rich text</b>")
        )
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Test",
            recipients = listOf(Uuid.random()),
            content = content
        )
        assertEquals(2, message.content.size)
    }

    @Test
    fun `Message bmlTemplate defaults to null`() {
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            subject = "Test",
            recipients = listOf(Uuid.random()),
            content = emptyList()
        )
        assertNull(message.bmlTemplate)
    }

    @Test
    fun `Message carries a BML message template reference with its payload`() {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"courseName":"ET"}""")
        val message = Message(
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(Uuid.random()),
            bmlTemplate = MessageBmlTemplate(project = "acme", templateKey = "welcome", payload = payload)
        )
        assertEquals("acme", message.bmlTemplate?.project)
        assertEquals("welcome", message.bmlTemplate?.templateKey)
        assertEquals(payload, message.bmlTemplate?.payload)
    }

    @Test
    fun `Message carries a multi-channel BML template reference`() {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"channelId":"planning"}""")
        val message = Message(
            channels = listOf(MessageChannel.EMAIL, MessageChannel.PUSH),
            recipients = listOf(Uuid.random()),
            bmlTemplate = MessageBmlTemplate("acme", "chat-message", payload),
        )

        assertEquals("chat-message", message.bmlTemplate?.templateKey)
        assertEquals(payload, message.bmlTemplate?.payload)
    }

    @Test
    fun `MessageBmlTemplate payload defaults to null`() {
        val template = MessageBmlTemplate(project = "acme", templateKey = "welcome")
        assertNull(template.payload)
    }
}
