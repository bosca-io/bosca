package bosca.ai.agents.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerRegistrationInputTest {

    private val config = JsonObject(mapOf("url" to JsonPrimitive("http://localhost:8080")))

    @Test
    fun `McpServerRegistrationInput stores all explicit properties`() {
        val input = McpServerRegistrationInput(
            key = "mcp-1",
            name = "MCP Server 1",
            description = "A test server",
            transportType = McpTransportType.SSE,
            configuration = config,
            enabled = false
        )
        assertEquals("mcp-1", input.key)
        assertEquals("MCP Server 1", input.name)
        assertEquals("A test server", input.description)
        assertEquals(McpTransportType.SSE, input.transportType)
        assertEquals(config, input.configuration)
        assertEquals(false, input.enabled)
    }

    @Test
    fun `McpServerRegistrationInput description defaults to empty string`() {
        val input = McpServerRegistrationInput(
            key = "k",
            name = "n",
            transportType = McpTransportType.STDIO,
            configuration = config
        )
        assertEquals("", input.description)
    }

    @Test
    fun `McpServerRegistrationInput enabled defaults to true`() {
        val input = McpServerRegistrationInput(
            key = "k",
            name = "n",
            transportType = McpTransportType.STREAMABLE_HTTP,
            configuration = config
        )
        assertTrue(input.enabled)
    }

    @Test
    fun `McpServerRegistrationInput equality`() {
        val a = McpServerRegistrationInput("k", "n", transportType = McpTransportType.SSE, configuration = config)
        val b = McpServerRegistrationInput("k", "n", transportType = McpTransportType.SSE, configuration = config)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `McpServerRegistrationInput with each transport type`() {
        for (type in McpTransportType.entries) {
            val input = McpServerRegistrationInput("k", "n", transportType = type, configuration = config)
            assertEquals(type, input.transportType)
        }
    }
}
