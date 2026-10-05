package bosca.ai.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessagePartTest {

    // --- ChatMessagePart ---

    @Test
    fun `ChatMessagePart stores type and text`() {
        val part = ChatMessagePart("text", "hello")
        assertEquals("text", part.type)
        assertEquals("hello", part.text)
    }

    // --- ChatMessagePartInput ---

    @Test
    fun `ChatMessagePartInput stores type and text`() {
        val input = ChatMessagePartInput("image", "url")
        assertEquals("image", input.type)
        assertEquals("url", input.text)
    }

    // --- ChatMessageInput ---

    @Test
    fun `ChatMessageInput stores role and parts`() {
        val parts = listOf(ChatMessagePartInput("text", "hi"))
        val input = ChatMessageInput("user", parts)
        assertEquals("user", input.role)
        assertEquals(1, input.parts.size)
        assertEquals("hi", input.parts[0].text)
    }

    // --- ChatRequest ---

    @Test
    fun `ChatRequest stores id messages and trigger`() {
        val msg = ChatMessage("m1", "user", listOf(ChatMessagePart("text", "hi")))
        val request = ChatRequest(id = "r1", messages = listOf(msg), trigger = "send")
        assertEquals("r1", request.id)
        assertEquals(1, request.messages.size)
        assertEquals("send", request.trigger)
    }

    @Test
    fun `ChatRequest sessionId defaults to null`() {
        val request = ChatRequest(id = "r1", messages = emptyList(), trigger = "send")
        assertEquals(null, request.sessionId)
    }

    @Test
    fun `ChatRequest sessionId can be set`() {
        val request = ChatRequest(id = "r1", messages = emptyList(), trigger = "send", sessionId = "s1")
        assertEquals("s1", request.sessionId)
    }

    // --- MessageStart ---

    @Test
    fun `MessageStart has default type start`() {
        val event = MessageStart(messageId = "m1")
        assertEquals("start", event.type)
        assertEquals("m1", event.messageId)
    }

    // --- MessageFinish ---

    @Test
    fun `MessageFinish has default type finish`() {
        val event = MessageFinish()
        assertEquals("finish", event.type)
    }

    // --- ReasoningStart ---

    @Test
    fun `ReasoningStart has correct defaults`() {
        val event = ReasoningStart(id = "r1")
        assertEquals("reasoning-start", event.type)
        assertEquals("r1", event.id)
    }

    // --- ReasoningDelta ---

    @Test
    fun `ReasoningDelta stores id and delta`() {
        val event = ReasoningDelta(id = "r1", delta = "thinking...")
        assertEquals("reasoning-delta", event.type)
        assertEquals("r1", event.id)
        assertEquals("thinking...", event.delta)
    }

    // --- ReasoningEnd ---

    @Test
    fun `ReasoningEnd has correct defaults`() {
        val event = ReasoningEnd(id = "r1")
        assertEquals("reasoning-end", event.type)
        assertEquals("r1", event.id)
    }

    // --- TextStart ---

    @Test
    fun `TextStart has correct defaults`() {
        val event = TextStart(id = "t1")
        assertEquals("text-start", event.type)
        assertEquals("t1", event.id)
    }

    // --- TextDelta ---

    @Test
    fun `TextDelta stores id and delta`() {
        val event = TextDelta(id = "t1", delta = "Hello")
        assertEquals("text-delta", event.type)
        assertEquals("t1", event.id)
        assertEquals("Hello", event.delta)
    }

    // --- TextEnd ---

    @Test
    fun `TextEnd has correct defaults`() {
        val event = TextEnd(id = "t1")
        assertEquals("text-end", event.type)
        assertEquals("t1", event.id)
    }
}
