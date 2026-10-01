package bosca.ai.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatEventTest {

    @Test
    fun `MessageStartEvent has correct type`() {
        val event = MessageStartEvent(messageId = "msg-1")
        assertEquals("start", event.type)
        assertEquals("msg-1", event.messageId)
    }

    @Test
    fun `MessageFinishEvent has correct type`() {
        val event = MessageFinishEvent()
        assertEquals("finish", event.type)
    }

    @Test
    fun `ReasoningStartEvent has correct type and id`() {
        val event = ReasoningStartEvent(id = "r-1")
        assertEquals("reasoning-start", event.type)
        assertEquals("r-1", event.id)
    }

    @Test
    fun `ReasoningDeltaEvent has correct type, id, and delta`() {
        val event = ReasoningDeltaEvent(id = "r-1", delta = "thinking...")
        assertEquals("reasoning-delta", event.type)
        assertEquals("r-1", event.id)
        assertEquals("thinking...", event.delta)
    }

    @Test
    fun `ReasoningEndEvent has correct type and id`() {
        val event = ReasoningEndEvent(id = "r-1")
        assertEquals("reasoning-end", event.type)
    }

    @Test
    fun `TextStartEvent has correct type`() {
        val event = TextStartEvent(id = "t-1")
        assertEquals("text-start", event.type)
        assertEquals("t-1", event.id)
    }

    @Test
    fun `TextDeltaEvent has correct type and delta`() {
        val event = TextDeltaEvent(id = "t-1", delta = "Hello")
        assertEquals("text-delta", event.type)
        assertEquals("Hello", event.delta)
    }

    @Test
    fun `TextEndEvent has correct type`() {
        val event = TextEndEvent(id = "t-1")
        assertEquals("text-end", event.type)
    }

    @Test
    fun `ToolRequestEvent has correct type and options`() {
        val options = listOf(
            ToolRequestOption(key = "yes", label = "Yes", description = "Confirm"),
            ToolRequestOption(key = "no", label = "No")
        )
        val event = ToolRequestEvent(
            requestId = "req-1",
            title = "Confirm?",
            message = "Are you sure?",
            options = options
        )
        assertEquals("tool-request", event.type)
        assertEquals("req-1", event.requestId)
        assertEquals(2, event.options.size)
        assertEquals("yes", event.options[0].key)
        assertEquals(null, event.options[1].description)
    }

    @Test
    fun `all ChatEvent subtypes implement ChatEvent interface`() {
        val events: List<ChatEvent> = listOf(
            MessageStartEvent(messageId = "1"),
            MessageFinishEvent(),
            ReasoningStartEvent(id = "1"),
            ReasoningDeltaEvent(id = "1", delta = "d"),
            ReasoningEndEvent(id = "1"),
            TextStartEvent(id = "1"),
            TextDeltaEvent(id = "1", delta = "d"),
            TextEndEvent(id = "1"),
            ToolRequestEvent(requestId = "1", title = "t", message = "m", options = emptyList())
        )
        assertEquals(9, events.size)
    }
}
