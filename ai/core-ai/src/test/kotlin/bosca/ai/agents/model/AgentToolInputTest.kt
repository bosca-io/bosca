package bosca.ai.agents.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AgentToolInputTest {

    private val scriptId = Uuid.random()
    private val mcpServerId = Uuid.random()

    @Test
    fun `AgentToolInput stores all explicit properties`() {
        val config = JsonPrimitive("cfg")
        val input = AgentToolInput(
            key = "search",
            name = "Search Tool",
            description = "Searches things",
            configuration = config,
            scriptId = scriptId,
            mcpServerId = mcpServerId
        )
        assertEquals("search", input.key)
        assertEquals("Search Tool", input.name)
        assertEquals("Searches things", input.description)
        assertEquals(config, input.configuration)
        assertEquals(scriptId, input.scriptId)
        assertEquals(mcpServerId, input.mcpServerId)
    }

    @Test
    fun `AgentToolInput configuration defaults to null`() {
        val input = AgentToolInput(key = "k", name = "n", description = "d")
        assertNull(input.configuration)
    }

    @Test
    fun `AgentToolInput scriptId defaults to null`() {
        val input = AgentToolInput(key = "k", name = "n", description = "d")
        assertNull(input.scriptId)
    }

    @Test
    fun `AgentToolInput mcpServerId defaults to null`() {
        val input = AgentToolInput(key = "k", name = "n", description = "d")
        assertNull(input.mcpServerId)
    }

    @Test
    fun `AgentToolInput equality`() {
        val a = AgentToolInput("k", "n", "d")
        val b = AgentToolInput("k", "n", "d")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
