package bosca.ai.agents.git

import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentRepoFileParserTest {

    private val parser = AgentRepoFileParser()

    // --- agents ---

    @Test
    fun `parse minimal agent file`() {
        val content = """
            ---
            key: research-assistant
            name: Research Assistant
            model: claude-opus-4-7
            prompt: research-system
            ---

            # Research Assistant

            Long-form description here.
        """.trimIndent()
        val result = parser.parse("agents/research-assistant.md", content)
        val agent = assertIs<ParsedFile.Agent>(result).file
        assertEquals("research-assistant", agent.key)
        assertEquals("Research Assistant", agent.name)
        assertEquals("claude-opus-4-7", agent.modelKey)
        assertEquals("research-system", agent.promptKey)
        assertEquals(emptyList(), agent.subAgentKeys)
        assertEquals(emptyList(), agent.toolKeys)
        assertNull(agent.configuration)
        assertTrue(agent.description.startsWith("# Research Assistant"))
    }

    @Test
    fun `parse agent with sub_agents tools and configuration`() {
        val content = """
            ---
            key: a
            name: A
            model: m
            prompt: p
            sub_agents:
              - b
              - c
            tools:
              - t1
              - t2
            configuration:
              temperature: 0.7
              max_tokens: 4096
            ---
            body
        """.trimIndent()
        val result = parser.parse("agents/a.md", content)
        val agent = assertIs<ParsedFile.Agent>(result).file
        assertEquals(listOf("b", "c"), agent.subAgentKeys)
        assertEquals(listOf("t1", "t2"), agent.toolKeys)
        val config = assertIs<JsonObject>(agent.configuration)
        assertEquals(JsonPrimitive(0.7), config["temperature"])
        assertEquals(JsonPrimitive(4096), config["max_tokens"])
    }

    // --- tools ---

    @Test
    fun `parse tool with mcp_server`() {
        val content = """
            ---
            key: web_search
            name: Web Search
            mcp_server: context7
            ---
        """.trimIndent()
        val result = parser.parse("tools/web_search.md", content)
        val tool = assertIs<ParsedFile.Tool>(result).file
        assertEquals("context7", tool.mcpServerKey)
        assertNull(tool.scriptKey)
    }

    @Test
    fun `parse tool with script`() {
        val content = """
            ---
            key: calc
            name: Calculator
            script: calc_script
            ---
        """.trimIndent()
        val tool = assertIs<ParsedFile.Tool>(parser.parse("tools/calc.md", content)).file
        assertEquals("calc_script", tool.scriptKey)
        assertNull(tool.mcpServerKey)
    }

    @Test
    fun `parse tool with neither mcp_server nor script`() {
        val content = """
            ---
            key: noop
            name: No-op
            ---
        """.trimIndent()
        val tool = assertIs<ParsedFile.Tool>(parser.parse("tools/noop.md", content)).file
        assertNull(tool.scriptKey)
        assertNull(tool.mcpServerKey)
    }

    // --- mcp servers ---

    @Test
    fun `parse mcp server with all required fields`() {
        val content = """
            ---
            key: context7
            name: Context7 Docs
            transport_type: STREAMABLE_HTTP
            configuration:
              url: https://mcp.context7.com/mcp
            ---
        """.trimIndent()
        val mcp = assertIs<ParsedFile.McpServer>(parser.parse("mcp-servers/context7.md", content)).file
        assertEquals("context7", mcp.key)
        assertEquals(McpTransportType.STREAMABLE_HTTP, mcp.transportType)
        assertEquals(true, mcp.enabled)
        val config = assertIs<JsonObject>(mcp.configuration)
        assertEquals(JsonPrimitive("https://mcp.context7.com/mcp"), config["url"])
    }

    @Test
    fun `parse mcp server with enabled false`() {
        val content = """
            ---
            key: m
            name: M
            transport_type: SSE
            configuration: {}
            enabled: false
            ---
        """.trimIndent()
        val mcp = assertIs<ParsedFile.McpServer>(parser.parse("mcp-servers/m.md", content)).file
        assertEquals(false, mcp.enabled)
        assertEquals(McpTransportType.SSE, mcp.transportType)
    }

    @Test
    fun `parse mcp server with invalid transport_type returns ParseError`() {
        val content = """
            ---
            key: m
            name: M
            transport_type: HTTP2
            configuration: {}
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("mcp-servers/m.md", content))
        assertTrue(err.message.contains("invalid transport_type 'HTTP2'"))
    }

    // --- prompts ---

    @Test
    fun `parse prompt with system and user sections`() {
        val content = """
            ---
            key: research-sys
            name: Research System
            description: System prompt for research
            input_type: text/plain
            output_type: text/plain
            ---

            ## System Prompt

            You are a research assistant.

            ## User Prompt

            Research: {{topic}}
        """.trimIndent()
        val prompt = assertIs<ParsedFile.Prompt>(parser.parse("prompts/research-sys.md", content)).file
        assertEquals("You are a research assistant.", prompt.systemPrompt)
        assertEquals("Research: {{topic}}", prompt.userPrompt)
    }

    @Test
    fun `parse prompt missing System Prompt section returns ParseError`() {
        val content = """
            ---
            key: p
            name: P
            description: d
            input_type: text/plain
            output_type: text/plain
            ---

            ## User Prompt

            Body only.
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("prompts/p.md", content))
        assertTrue(err.message.contains("must contain both"))
    }

    @Test
    fun `parse prompt with sections in wrong order returns ParseError`() {
        val content = """
            ---
            key: p
            name: P
            description: d
            input_type: text/plain
            output_type: text/plain
            ---

            ## User Prompt

            U

            ## System Prompt

            S
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("prompts/p.md", content))
        assertTrue(err.message.contains("must contain both"))
    }

    @Test
    fun `parse prompt with empty user section returns ParseError`() {
        val content = """
            ---
            key: p
            name: P
            description: d
            input_type: text/plain
            output_type: text/plain
            ---

            ## System Prompt

            S

            ## User Prompt

        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("prompts/p.md", content))
        assertTrue(err.message.contains("must contain both"))
    }

    // --- resources ---

    @Test
    fun `parse resource with static_text`() {
        val content = """
            ---
            key: welcome
            name: Welcome
            static_text: Hello there
            ---

            A greeting resource.
        """.trimIndent()
        val resource = assertIs<ParsedFile.Resource>(parser.parse("resources/welcome.md", content)).file
        assertEquals("welcome", resource.key)
        assertEquals("Welcome", resource.name)
        assertEquals("Hello there", resource.staticText)
        assertEquals("A greeting resource.", resource.description)
        assertNull(resource.metadataSlug)
        assertNull(resource.scriptKey)
    }

    @Test
    fun `parse resource with metadata slug`() {
        val content = """
            ---
            key: article
            name: Article
            metadata: my-article
            ---
        """.trimIndent()
        val resource = assertIs<ParsedFile.Resource>(parser.parse("resources/article.md", content)).file
        assertEquals("my-article", resource.metadataSlug)
        assertNull(resource.staticText)
    }

    @Test
    fun `parse resource with document_metadata and document_version`() {
        val content = """
            ---
            key: doc
            name: Doc
            document_metadata: the-doc
            document_version: 3
            ---
        """.trimIndent()
        val resource = assertIs<ParsedFile.Resource>(parser.parse("resources/doc.md", content)).file
        assertEquals("the-doc", resource.documentMetadataSlug)
        assertEquals(3, resource.documentVersion)
    }

    @Test
    fun `parse resource with graphql operation and transforms`() {
        val content = """
            ---
            key: q
            name: Q
            graphql_operation: "query { ping }"
            graphql_input_transform: in
            graphql_output_transform: out
            ---
        """.trimIndent()
        val resource = assertIs<ParsedFile.Resource>(parser.parse("resources/q.md", content)).file
        assertEquals("query { ping }", resource.graphqlOperation)
        assertEquals("in", resource.graphqlInputTransform)
        assertEquals("out", resource.graphqlOutputTransform)
    }

    @Test
    fun `parse resource with non-integer document_version returns ParseError`() {
        val content = """
            ---
            key: doc
            name: Doc
            document_metadata: the-doc
            document_version: latest
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("resources/doc.md", content))
        assertTrue(err.message.contains("field 'document_version' must be an integer"))
    }

    // --- frontmatter errors ---

    @Test
    fun `missing opening frontmatter delimiter is a ParseError`() {
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", "# Just a heading\n"))
        assertTrue(err.message.contains("must start with YAML frontmatter"))
    }

    @Test
    fun `missing closing frontmatter delimiter is a ParseError`() {
        val content = "---\nkey: a\n"
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        assertTrue(err.message.contains("not closed"))
    }

    @Test
    fun `malformed YAML is a ParseError`() {
        // colon-space-newline immediately is not valid YAML for a mapping value.
        val content = "---\nkey:\n  -\n  : broken\n---\nbody"
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        assertTrue(err.message.contains("malformed YAML") || err.message.contains("missing required"))
    }

    @Test
    fun `missing required key field is a ParseError`() {
        val content = """
            ---
            name: A
            model: m
            prompt: p
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        assertTrue(err.message.contains("missing required field 'key'"))
    }

    @Test
    fun `wrong field type is a ParseError`() {
        val content = """
            ---
            key: a
            name: 123
            model: m
            prompt: p
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        assertTrue(err.message.contains("field 'name' must be a string"))
    }

    @Test
    fun `multiple errors are reported together`() {
        val content = """
            ---
            name: A
            tools: not-a-list
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        assertTrue(err.message.contains("missing required field 'key'"))
        assertTrue(err.message.contains("missing required field 'model'"))
        assertTrue(err.message.contains("missing required field 'prompt'"))
        assertTrue(err.message.contains("field 'tools' must be a list"))
    }

    // --- path routing ---

    @Test
    fun `file outside known directories returns UnknownPath`() {
        val result = parser.parse("README.md", "---\nkey: x\n---\n")
        assertEquals(ParsedFile.UnknownPath("README.md"), result)
    }

    @Test
    fun `file in unknown subdirectory returns UnknownPath`() {
        val result = parser.parse("misc/foo.md", "---\nkey: x\n---\n")
        assertEquals(ParsedFile.UnknownPath("misc/foo.md"), result)
    }

    // --- line endings ---

    @Test
    fun `CRLF line endings are accepted`() {
        val content = "---\r\nkey: a\r\nname: A\r\nmodel: m\r\nprompt: p\r\n---\r\nbody\r\n"
        val agent = assertIs<ParsedFile.Agent>(parser.parse("agents/a.md", content)).file
        assertEquals("a", agent.key)
        assertEquals("body", agent.description)
    }

    // --- configuration JsonElement conversion ---

    @Test
    fun `configuration with nested map and list converts to JsonElement`() {
        val content = """
            ---
            key: a
            name: A
            model: m
            prompt: p
            configuration:
              flags:
                - one
                - two
              limits:
                max: 10
                min: 1
            ---
        """.trimIndent()
        val agent = assertIs<ParsedFile.Agent>(parser.parse("agents/a.md", content)).file
        val config = assertIs<JsonObject>(agent.configuration)
        val flags = assertIs<JsonArray>(config["flags"])
        assertEquals(JsonPrimitive("one"), flags[0])
        val limits = assertIs<JsonObject>(config["limits"])
        assertEquals(JsonPrimitive(10), limits["max"])
    }

    // --- helper ---

    @Test
    fun `agentRepoPath builds canonical paths`() {
        assertEquals("agents/foo.md", agentRepoPath(AgentRepoLayout.AGENTS_DIR, "foo"))
        assertEquals("tools/bar.md", agentRepoPath(AgentRepoLayout.TOOLS_DIR, "bar"))
        assertEquals("mcp-servers/baz.md", agentRepoPath(AgentRepoLayout.MCP_SERVERS_DIR, "baz"))
        assertEquals("prompts/qux.md", agentRepoPath(AgentRepoLayout.PROMPTS_DIR, "qux"))
        assertEquals("resources/quux.md", agentRepoPath(AgentRepoLayout.RESOURCES_DIR, "quux"))
    }

    @Test
    fun `parser fails loudly when required field is wrong type and not via fall-through`() {
        // Regression guard: an Int value in a String-required field must surface as
        // 'must be a string', not as 'missing required field'.
        val content = """
            ---
            key: 42
            name: A
            model: m
            prompt: p
            ---
        """.trimIndent()
        val err = assertIs<ParsedFile.ParseError>(parser.parse("agents/a.md", content))
        if (!err.message.contains("field 'key' must be a string")) {
            fail("expected key-must-be-string error, got: ${err.message}")
        }
    }
}
