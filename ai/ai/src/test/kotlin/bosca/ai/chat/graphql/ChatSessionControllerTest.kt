package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ChatSessionControllerTest {

    private val controller = ChatSessionController(mockk<ChatHistoryService>())

    @Test
    fun `parentSessionId resolves nested session parent`() {
        val parentSessionId = Uuid.random()
        val session = ChatSession(
            principalId = Uuid.random(),
            parentSessionId = parentSessionId,
            agentKey = "agent",
        )

        assertEquals(parentSessionId, controller.parentSessionId(session))
    }

    @Test
    fun `parentSessionId is null for root session`() {
        val session = ChatSession(
            principalId = Uuid.random(),
            agentKey = "agent",
        )

        assertNull(controller.parentSessionId(session))
    }
}
