package bosca.ai.agents.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class McpServerRegistrationTest {

    private val config = JsonObject(emptyMap())

    // --- McpServerRegistration ---

    @Test
    fun `McpServerRegistration id defaults to NIL`() {
        val reg = McpServerRegistration(key = "k", name = "n", transportType = McpTransportType.SSE, configuration = config)
        assertEquals(Uuid.NIL, reg.id)
    }

    @Test
    fun `McpServerRegistration enabled defaults to true`() {
        val reg = McpServerRegistration(key = "k", name = "n", transportType = McpTransportType.SSE, configuration = config)
        assertTrue(reg.enabled)
    }

    @Test
    fun `McpServerRegistration description defaults to empty`() {
        val reg = McpServerRegistration(key = "k", name = "n", transportType = McpTransportType.SSE, configuration = config)
        assertEquals("", reg.description)
    }

    @Test
    fun `McpServerRegistration stores all properties`() {
        val id = Uuid.random()
        val reg = McpServerRegistration(
            id = id, key = "my-mcp", name = "My MCP", description = "Test",
            transportType = McpTransportType.STREAMABLE_HTTP, configuration = config, enabled = false
        )
        assertEquals(id, reg.id)
        assertEquals("my-mcp", reg.key)
        assertEquals("My MCP", reg.name)
        assertEquals("Test", reg.description)
        assertEquals(McpTransportType.STREAMABLE_HTTP, reg.transportType)
        assertEquals(false, reg.enabled)
    }

    // --- McpServerRegistrationInput ---

    @Test
    fun `McpServerRegistrationInput enabled defaults to true`() {
        val input = McpServerRegistrationInput(key = "k", name = "n", transportType = McpTransportType.STDIO, configuration = config)
        assertTrue(input.enabled)
    }

    @Test
    fun `McpServerRegistrationInput description defaults to empty`() {
        val input = McpServerRegistrationInput(key = "k", name = "n", transportType = McpTransportType.STDIO, configuration = config)
        assertEquals("", input.description)
    }

    @Test
    fun `McpServerRegistrationInput stores all properties`() {
        val input = McpServerRegistrationInput(
            key = "k", name = "n", description = "desc",
            transportType = McpTransportType.SSE, configuration = config, enabled = false
        )
        assertEquals("k", input.key)
        assertEquals("desc", input.description)
        assertEquals(McpTransportType.SSE, input.transportType)
        assertEquals(false, input.enabled)
    }
}
