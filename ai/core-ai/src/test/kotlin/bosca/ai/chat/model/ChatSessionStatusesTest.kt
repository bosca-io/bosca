package bosca.ai.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSessionStatusesTest {

    @Test
    fun `ChatSessionStatuses stores status`() {
        val statuses = ChatSessionStatuses(status = ChatSessionStatus.STREAMING)
        assertEquals(ChatSessionStatus.STREAMING, statuses.status)
    }

    @Test
    fun `ChatSessionStatuses with each status value`() {
        for (status in ChatSessionStatus.entries) {
            val statuses = ChatSessionStatuses(status = status)
            assertEquals(status, statuses.status)
        }
    }

    @Test
    fun `ChatSessionStatuses equality`() {
        val a = ChatSessionStatuses(ChatSessionStatus.COMPLETED)
        val b = ChatSessionStatuses(ChatSessionStatus.COMPLETED)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatSessionStatuses copy changes status`() {
        val original = ChatSessionStatuses(ChatSessionStatus.STREAMING)
        val updated = original.copy(status = ChatSessionStatus.FAILED)
        assertEquals(ChatSessionStatus.FAILED, updated.status)
    }
}
