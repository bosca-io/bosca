package bosca.ai.chat.model

import bosca.analytics.model.AnalyticsVisualizationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatEventSerializationTest {

    private val json = Json { encodeDefaults = true }

    // --- ToolDisplayEvent (not covered by existing ChatEventTest) ---

    @Test
    fun `ToolDisplayEvent stores all properties`() {
        val config = JsonObject(mapOf("x" to JsonPrimitive("axis")))
        val data = listOf(JsonObject(mapOf("value" to JsonPrimitive(42))))
        val event = ToolDisplayEvent(
            requestId = "d1", title = "Sales Chart",
            visualizationType = AnalyticsVisualizationType.BAR, configuration = config, data = data
        )
        assertEquals("tool-display", event.type)
        assertEquals("d1", event.requestId)
        assertEquals("Sales Chart", event.title)
        assertEquals(AnalyticsVisualizationType.BAR, event.visualizationType)
        assertEquals(1, event.data.size)
    }

    @Test
    fun `ToolDisplayEvent implements ChatEvent`() {
        val event: ChatEvent = ToolDisplayEvent(
            requestId = "d1", title = "T",
            visualizationType = AnalyticsVisualizationType.TABLE,
            configuration = JsonObject(emptyMap()),
            data = emptyList()
        )
        assertIs<ToolDisplayEvent>(event)
    }

    // --- Concrete serialization (events are serialized individually, not polymorphically) ---

    @Test
    fun `MessageStartEvent serializes with type field`() {
        val event = MessageStartEvent(messageId = "m1")
        val serialized = json.encodeToString(MessageStartEvent.serializer(), event)
        assert(serialized.contains("\"type\":\"start\""))
        assert(serialized.contains("\"messageId\":\"m1\""))
    }

    @Test
    fun `MessageFinishEvent round-trips`() {
        val original = MessageFinishEvent()
        val serialized = json.encodeToString(MessageFinishEvent.serializer(), original)
        val deserialized = json.decodeFromString(MessageFinishEvent.serializer(), serialized)
        assertIs<MessageFinishEvent>(deserialized)
        assertEquals("finish", deserialized.type)
    }

    @Test
    fun `TextDeltaEvent round-trips`() {
        val original = TextDeltaEvent(id = "t1", delta = "Hello")
        val serialized = json.encodeToString(TextDeltaEvent.serializer(), original)
        val deserialized = json.decodeFromString(TextDeltaEvent.serializer(), serialized)
        assertIs<TextDeltaEvent>(deserialized)
        assertEquals("t1", deserialized.id)
        assertEquals("Hello", deserialized.delta)
    }

    @Test
    fun `ToolRequestEvent round-trips`() {
        val original = ToolRequestEvent(
            requestId = "r1", title = "Title",
            message = "Msg", options = listOf(ToolRequestOption("a", "A"))
        )
        val serialized = json.encodeToString(ToolRequestEvent.serializer(), original)
        val deserialized = json.decodeFromString(ToolRequestEvent.serializer(), serialized)
        assertIs<ToolRequestEvent>(deserialized)
        assertEquals("r1", deserialized.requestId)
        assertEquals(1, deserialized.options.size)
    }

    @Test
    fun `ToolDisplayEvent round-trips`() {
        val config = JsonObject(mapOf("key" to JsonPrimitive("val")))
        val data = listOf(JsonObject(mapOf("x" to JsonPrimitive(1))))
        val original = ToolDisplayEvent(
            requestId = "d1", title = "Chart",
            visualizationType = AnalyticsVisualizationType.LINE, configuration = config, data = data,
            sourceQuery = "SELECT x FROM points"
        )
        val serialized = json.encodeToString(ToolDisplayEvent.serializer(), original)
        val deserialized = json.decodeFromString(ToolDisplayEvent.serializer(), serialized)
        assertIs<ToolDisplayEvent>(deserialized)
        assertEquals("d1", deserialized.requestId)
        assertEquals(AnalyticsVisualizationType.LINE, deserialized.visualizationType)
        assertEquals(1, deserialized.data.size)
        assertEquals("SELECT x FROM points", deserialized.sourceQuery)
    }

    @Test
    fun `ToolDisplayEvent decodes rows persisted before sourceQuery existed`() {
        val serialized = """{"type":"tool-display","requestId":"d1","title":"Chart","visualizationType":"TABLE","configuration":{},"data":[]}"""
        val deserialized = json.decodeFromString(ToolDisplayEvent.serializer(), serialized)
        assertEquals(null, deserialized.sourceQuery)
    }

    @Test
    fun `ReasoningDeltaEvent round-trips`() {
        val original = ReasoningDeltaEvent(id = "r1", delta = "thinking...")
        val serialized = json.encodeToString(ReasoningDeltaEvent.serializer(), original)
        val deserialized = json.decodeFromString(ReasoningDeltaEvent.serializer(), serialized)
        assertIs<ReasoningDeltaEvent>(deserialized)
        assertEquals("r1", deserialized.id)
        assertEquals("thinking...", deserialized.delta)
    }
}
