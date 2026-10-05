package bosca.ai.agents.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AgentToolTest {

    // --- AgentTool ---

    @Test
    fun `AgentTool id defaults to NIL`() {
        val tool = AgentTool(key = "k", name = "n", description = "d")
        assertEquals(Uuid.NIL, tool.id)
    }

    @Test
    fun `AgentTool optional fields default to null`() {
        val tool = AgentTool(key = "k", name = "n", description = "d")
        assertNull(tool.configuration)
        assertNull(tool.scriptId)
        assertNull(tool.mcpServerId)
    }

    @Test
    fun `AgentTool stores all properties`() {
        val scriptId = Uuid.random()
        val mcpServerId = Uuid.random()
        val tool = AgentTool(
            key = "search", name = "Search", description = "Web search",
            scriptId = scriptId, mcpServerId = mcpServerId
        )
        assertEquals("search", tool.key)
        assertEquals("Search", tool.name)
        assertEquals("Web search", tool.description)
        assertEquals(scriptId, tool.scriptId)
        assertEquals(mcpServerId, tool.mcpServerId)
    }

    // --- AgentToolInput ---

    @Test
    fun `AgentToolInput optional fields default to null`() {
        val input = AgentToolInput(key = "k", name = "n", description = "d")
        assertNull(input.configuration)
        assertNull(input.scriptId)
        assertNull(input.mcpServerId)
    }

    @Test
    fun `AgentToolInput stores all properties`() {
        val scriptId = Uuid.random()
        val input = AgentToolInput(key = "k", name = "n", description = "d", scriptId = scriptId)
        assertEquals("k", input.key)
        assertEquals(scriptId, input.scriptId)
    }
}
