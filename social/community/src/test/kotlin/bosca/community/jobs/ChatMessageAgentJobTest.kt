package bosca.community.jobs

import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatMessageAgentJobTest {

    private val channelId = Uuid.random()
    private val senderId = Uuid.random()

    @Test
    fun `ChatMessageAgentJob stores all explicit properties`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "message"))
        val attrs = JsonPrimitive("meta")
        val job = ChatMessageAgentJob(
            channelId = channelId,
            senderId = senderId,
            sequence = 42L,
            content = content,
            attributes = attrs
        )
        assertEquals(channelId, job.channelId)
        assertEquals(senderId, job.senderId)
        assertEquals(42L, job.sequence)
        assertEquals(content, job.content)
        assertEquals(attrs, job.attributes)
    }

    @Test
    fun `ChatMessageAgentJob attributes defaults to null`() {
        val job = ChatMessageAgentJob(channelId, senderId, 1L, emptyList())
        assertNull(job.attributes)
    }

    @Test
    fun `ChatMessageAgentJob equality`() {
        val content = listOf(MessageContent(MessageContentType.TEXT, "hi"))
        val a = ChatMessageAgentJob(channelId, senderId, 5L, content)
        val b = ChatMessageAgentJob(channelId, senderId, 5L, content)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatMessageAgentJob with empty content`() {
        val job = ChatMessageAgentJob(channelId, senderId, 0L, emptyList())
        assertEquals(emptyList(), job.content)
    }

    @Test
    fun `agent job retry reuses the response client id`() {
        val job = ChatMessageAgentJob(channelId, senderId, 42L, emptyList())

        assertEquals(
            ChatMessageAgentExecutor.responseClientId(job),
            ChatMessageAgentExecutor.responseClientId(job.copy()),
        )
        kotlin.test.assertNotEquals(
            ChatMessageAgentExecutor.responseClientId(job),
            ChatMessageAgentExecutor.responseClientId(job.copy(sequence = 43)),
        )
    }
}
