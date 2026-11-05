package bosca.ai.kit.agents

import ai.koog.prompt.message.Message
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.session.KitSessionServiceImpl
import bosca.ai.kit.configuration.KitJson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

/**
 * Proves [KitSessionServiceImpl] records the user's turn and Kit's reply to chat history as real Koog
 * [Message]s serialized with the Kit Json, so each conversational row decodes back to the same message.
 */
class KitSessionServiceImplTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Kit's own Json (the platform Json + Koog's reflective polymorphic types), as DI builds it.
    private val kitJson = KitJson(koogJson(json))
    private val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun recordingHistory(store: MutableList<ChatHistoryMessage>): ChatHistoryService =
        mockk<ChatHistoryService>(relaxed = true).also { history ->
            coEvery { history.addMessage(any()) } answers { store.add(firstArg()); firstArg() }
        }

    private fun decode(row: ChatHistoryMessage): Message = kitJson.json.decodeFromJsonElement(Message.serializer(), row.event)

    @Test
    fun `recordUserMessage stores a Koog User message that decodes back`() = runTest {
        val store = mutableListOf<ChatHistoryMessage>()

        KitSessionServiceImpl(recordingHistory(store), kitJson).recordUserMessage(
            sessionId,
            ChatMessageInput(
                role = "user",
                parts = listOf(ChatMessagePartInput(type = "text", text = "Hi"), ChatMessagePartInput(type = "text", text = "Kit")),
            ),
        )

        // The user's turn is ONE conversational row authored by the Koog User role, joining its text parts.
        val row = store.single()
        assertEquals(sessionId, row.sessionId)
        assertEquals("User", row.author)
        assertEquals("Hi\nKit", assertIs<Message.User>(decode(row)).textContent())
    }

    @Test
    fun `recordResponse stores a Koog Assistant message that decodes back`() = runTest {
        val store = mutableListOf<ChatHistoryMessage>()

        KitSessionServiceImpl(recordingHistory(store), kitJson).recordResponse(sessionId, KitResponse.Text("Hello from Kit"))

        val row = store.single()
        assertEquals(sessionId, row.sessionId)
        assertEquals("Assistant", row.author)
        assertEquals("Hello from Kit", assertIs<Message.Assistant>(decode(row)).textContent())
    }
}
