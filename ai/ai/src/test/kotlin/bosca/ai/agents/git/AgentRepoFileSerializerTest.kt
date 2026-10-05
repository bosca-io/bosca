package bosca.ai.agents.git

import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRepoFileSerializerTest {

    private val serializer = AgentRepoFileSerializer()
    private val parser = AgentRepoFileParser()

    // --- agent ---

    @Test
    fun `serialized agent starts with frontmatter delimiters`() {
        val file = AgentFile(
            path = "agents/a.md",
            key = "a",
            name = "A",
            description = "desc",
            modelKey = "m",
            promptKey = "p"
        )
        val text = serializer.serializeAgent(file)
        assertTrue(text.startsWith("---\n"))
        assertTrue(text.contains("\n---\n"))
        assertTrue(text.contains("key: a"))
        assertTrue(text.contains("name: A"))
        assertTrue(text.contains("model: m"))
        assertTrue(text.contains("prompt: p"))
        assertTrue(text.trim().endsWith("desc"))
    }

    @Test
    fun `serialized agent omits empty sub_agents and tools lists`() {
        val file = AgentFile(
            path = "agents/a.md",
            key = "a", name = "A", description = "",
            modelKey = "m", promptKey = "p"
        )
        val text = serializer.serializeAgent(file)
        assertTrue(!text.contains("sub_agents:"))
        assertTrue(!text.contains("tools:"))
    }

    @Test
    fun `serialized agent includes sub_agents and tools when non-empty`() {
        val file = AgentFile(
            path = "agents/a.md",
            key = "a", name = "A", description = "",
            modelKey = "m", promptKey = "p",
            subAgentKeys = listOf("b", "c"),
            toolKeys = listOf("t1")
        )
        val text = serializer.serializeAgent(file)
        assertTrue(text.contains("sub_agents:"))
        assertTrue(text.contains("- b"))
        assertTrue(text.contains("- c"))
        assertTrue(text.contains("tools:"))
        assertTrue(text.contains("- t1"))
    }

    // --- tool ---

    @Test
    fun `serialized tool omits mcp_server and script when null`() {
        val file = ToolFile(path = "tools/t.md", key = "t", name = "T", description = "")
        val text = serializer.serializeTool(file)
        assertTrue(!text.contains("mcp_server:"))
        assertTrue(!text.contains("script:"))
    }

    @Test
    fun `serialized tool includes only the set binding`() {
        val withMcp = ToolFile(path = "tools/t.md", key = "t", name = "T", description = "", mcpServerKey = "m")
        val text = serializer.serializeTool(withMcp)
        assertTrue(text.contains("mcp_server: m"))
        assertTrue(!text.contains("script:"))
    }

    // --- mcp server ---

    @Test
    fun `serialized mcp server includes transport_type and enabled`() {
        val file = McpServerFile(
            path = "mcp-servers/m.md",
            key = "m", name = "M", description = "",
            transportType = McpTransportType.STREAMABLE_HTTP,
            configuration = JsonObject(emptyMap()),
            enabled = false
        )
        val text = serializer.serializeMcpServer(file)
        assertTrue(text.contains("transport_type: STREAMABLE_HTTP"))
        assertTrue(text.contains("enabled: false"))
    }

    // --- resource ---

    @Test
    fun `serialized resource omits unset variants and includes the one set`() {
        val file = ResourceFile(
            path = "resources/r.md", key = "r", name = "R", description = "desc",
            staticText = "the content"
        )
        val text = serializer.serializeResource(file)
        assertTrue(text.contains("key: r"))
        assertTrue(text.contains("static_text: the content"))
        assertTrue(!text.contains("metadata:"))
        assertTrue(!text.contains("document_metadata:"))
        assertTrue(!text.contains("content_metadata:"))
        assertTrue(!text.contains("script:"))
        assertTrue(!text.contains("graphql_operation:"))
        // description is the body, like tools/agents
        assertTrue(text.trim().endsWith("desc"))
    }

    // --- prompt ---

    @Test
    fun `serialized prompt body has System Prompt and User Prompt headers`() {
        val file = PromptFile(
            path = "prompts/p.md",
            key = "p", name = "P", description = "d",
            inputType = "text/plain", outputType = "text/plain",
            systemPrompt = "S text", userPrompt = "U text"
        )
        val text = serializer.serializePrompt(file)
        assertTrue(text.contains("## System Prompt"))
        assertTrue(text.contains("## User Prompt"))
        assertTrue(text.contains("S text"))
        assertTrue(text.contains("U text"))
        // description is in frontmatter, NOT body
        assertTrue(text.contains("description: d"))
    }

    // --- round-trips ---

    @Test
    fun `agent round-trips through serialize and parse`() {
        val original = AgentFile(
            path = "agents/a.md",
            key = "a",
            name = "A",
            description = "Long description with **markdown**.",
            modelKey = "claude",
            promptKey = "p",
            subAgentKeys = listOf("b", "c"),
            toolKeys = listOf("t1", "t2"),
            configuration = buildJsonObject {
                put("temperature", 0.7)
                put("max_tokens", 4096)
            }
        )
        val text = serializer.serializeAgent(original)
        val parsed = assertIs<ParsedFile.Agent>(parser.parse(original.path, text)).file
        assertEquals(original.key, parsed.key)
        assertEquals(original.name, parsed.name)
        assertEquals(original.modelKey, parsed.modelKey)
        assertEquals(original.promptKey, parsed.promptKey)
        assertEquals(original.subAgentKeys, parsed.subAgentKeys)
        assertEquals(original.toolKeys, parsed.toolKeys)
        assertEquals(original.description, parsed.description)
        val cfg = assertIs<JsonObject>(parsed.configuration)
        assertEquals(JsonPrimitive(4096), cfg["max_tokens"])
    }

    @Test
    fun `tool with mcp_server round-trips`() {
        val original = ToolFile(
            path = "tools/web.md",
            key = "web", name = "Web", description = "Searches the web.",
            mcpServerKey = "context7"
        )
        val text = serializer.serializeTool(original)
        val parsed = assertIs<ParsedFile.Tool>(parser.parse(original.path, text)).file
        assertEquals(original, parsed)
    }

    @Test
    fun `tool with script round-trips`() {
        val original = ToolFile(
            path = "tools/calc.md",
            key = "calc", name = "Calculator", description = "Computes.",
            scriptKey = "calc_script"
        )
        val text = serializer.serializeTool(original)
        val parsed = assertIs<ParsedFile.Tool>(parser.parse(original.path, text)).file
        assertEquals(original, parsed)
    }

    @Test
    fun `mcp server round-trips`() {
        val original = McpServerFile(
            path = "mcp-servers/m.md",
            key = "m", name = "M", description = "An MCP.",
            transportType = McpTransportType.SSE,
            configuration = buildJsonObject { put("url", "https://example.com/mcp") },
            enabled = true
        )
        val text = serializer.serializeMcpServer(original)
        val parsed = assertIs<ParsedFile.McpServer>(parser.parse(original.path, text)).file
        assertEquals(original.key, parsed.key)
        assertEquals(original.transportType, parsed.transportType)
        assertEquals(original.enabled, parsed.enabled)
        assertEquals(original.configuration, parsed.configuration)
    }

    @Test
    fun `resource with static text round-trips`() {
        val original = ResourceFile(
            path = "resources/welcome.md",
            key = "welcome", name = "Welcome", description = "A greeting resource.",
            staticText = "Hello there"
        )
        val text = serializer.serializeResource(original)
        val parsed = assertIs<ParsedFile.Resource>(parser.parse(original.path, text)).file
        assertEquals(original, parsed)
    }

    @Test
    fun `resource with document metadata and version round-trips`() {
        val original = ResourceFile(
            path = "resources/doc.md",
            key = "doc", name = "Doc", description = "A pinned document.",
            documentMetadataSlug = "the-doc", documentVersion = 3
        )
        val text = serializer.serializeResource(original)
        val parsed = assertIs<ParsedFile.Resource>(parser.parse(original.path, text)).file
        assertEquals(original, parsed)
    }

    @Test
    fun `resource with graphql operation and transforms round-trips`() {
        val original = ResourceFile(
            path = "resources/q.md",
            key = "q", name = "Q", description = "A pinned query.",
            graphqlOperation = "query { ping }",
            graphqlInputTransform = "in",
            graphqlOutputTransform = "out"
        )
        val text = serializer.serializeResource(original)
        val parsed = assertIs<ParsedFile.Resource>(parser.parse(original.path, text)).file
        assertEquals(original, parsed)
    }

    @Test
    fun `prompt round-trips`() {
        val original = PromptFile(
            path = "prompts/p.md",
            key = "p", name = "P", description = "A prompt.",
            inputType = "text/plain", outputType = "text/plain",
            systemPrompt = "You are a helpful assistant.",
            userPrompt = "Do: {{task}}"
        )
        val text = serializer.serializePrompt(original)
        val parsed = assertIs<ParsedFile.Prompt>(parser.parse(original.path, text)).file
        assertEquals(original.key, parsed.key)
        assertEquals(original.description, parsed.description)
        assertEquals(original.inputType, parsed.inputType)
        assertEquals(original.outputType, parsed.outputType)
        assertEquals(original.systemPrompt, parsed.systemPrompt)
        assertEquals(original.userPrompt, parsed.userPrompt)
    }
}
