package bosca.ai.chat.model

import bosca.analytics.model.AnalyticsVisualizationType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatEventToolTest {

    // --- ToolRequestOption ---

    @Test
    fun `ToolRequestOption stores key and label`() {
        val option = ToolRequestOption(key = "yes", label = "Yes")
        assertEquals("yes", option.key)
        assertEquals("Yes", option.label)
    }

    @Test
    fun `ToolRequestOption description defaults to null`() {
        val option = ToolRequestOption(key = "k", label = "L")
        assertNull(option.description)
    }

    @Test
    fun `ToolRequestOption with description`() {
        val option = ToolRequestOption(key = "k", label = "L", description = "Detailed info")
        assertEquals("Detailed info", option.description)
    }

    @Test
    fun `ToolRequestOption equality`() {
        val a = ToolRequestOption("k", "L", "D")
        val b = ToolRequestOption("k", "L", "D")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- ToolRequestEvent ---

    @Test
    fun `ToolRequestEvent stores all properties`() {
        val options = listOf(
            ToolRequestOption("yes", "Yes"),
            ToolRequestOption("no", "No")
        )
        val event = ToolRequestEvent(
            requestId = "req-1",
            title = "Confirm",
            message = "Are you sure?",
            options = options
        )
        assertEquals("tool-request", event.type)
        assertEquals("req-1", event.requestId)
        assertEquals("Confirm", event.title)
        assertEquals("Are you sure?", event.message)
        assertEquals(2, event.options.size)
    }

    // --- ToolDisplayEvent ---

    @Test
    fun `ToolDisplayEvent stores all properties`() {
        val config = JsonObject(mapOf("xAxis" to JsonPrimitive("date")))
        val data = listOf(JsonObject(mapOf("value" to JsonPrimitive(42))))
        val event = ToolDisplayEvent(
            requestId = "disp-1",
            title = "Chart",
            visualizationType = AnalyticsVisualizationType.BAR,
            configuration = config,
            data = data
        )
        assertEquals("tool-display", event.type)
        assertEquals("disp-1", event.requestId)
        assertEquals("Chart", event.title)
        assertEquals(AnalyticsVisualizationType.BAR, event.visualizationType)
        assertEquals(config, event.configuration)
        assertEquals(1, event.data.size)
    }

    @Test
    fun `ToolDisplayEvent with empty data`() {
        val config = JsonObject(emptyMap())
        val event = ToolDisplayEvent(
            requestId = "d1",
            title = "Empty",
            visualizationType = AnalyticsVisualizationType.TABLE,
            configuration = config,
            data = emptyList()
        )
        assertEquals(emptyList(), event.data)
    }

    // --- ChatEvent type discriminators ---

    @Test
    fun `MessageStartEvent has default type start`() {
        val event = MessageStartEvent(messageId = "m1")
        assertEquals("start", event.type)
    }

    @Test
    fun `MessageFinishEvent has default type finish`() {
        val event = MessageFinishEvent()
        assertEquals("finish", event.type)
    }

    @Test
    fun `ReasoningStartEvent has correct type`() {
        val event = ReasoningStartEvent(id = "r1")
        assertEquals("reasoning-start", event.type)
    }

    @Test
    fun `ReasoningDeltaEvent stores delta`() {
        val event = ReasoningDeltaEvent(id = "r1", delta = "thinking")
        assertEquals("reasoning-delta", event.type)
        assertEquals("thinking", event.delta)
    }

    @Test
    fun `TextDeltaEvent stores delta`() {
        val event = TextDeltaEvent(id = "t1", delta = "hello")
        assertEquals("text-delta", event.type)
        assertEquals("hello", event.delta)
    }

    @Test
    fun `TextEndEvent has correct type`() {
        val event = TextEndEvent(id = "t1")
        assertEquals("text-end", event.type)
    }
}
