package bosca.ai.agents.git

import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentRepoValidatorTest {

    private val validator: AgentRepoValidator = AgentRepoValidatorImpl()

    // --- happy path ---

    @Test
    fun `empty snapshot is Ok`() {
        val result = validator.validate(AgentRepoTreeSnapshot())
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `valid full graph is Ok`() {
        val snapshot = AgentRepoTreeSnapshot(
            mcpServers = listOf(mcp(key = "context7")),
            tools = listOf(tool(key = "web_search", mcpServerKey = "context7")),
            prompts = listOf(prompt(key = "research-sys")),
            agents = listOf(
                agent(key = "leaf", modelKey = "claude", promptKey = "research-sys"),
                agent(
                    key = "root",
                    modelKey = "claude",
                    promptKey = "research-sys",
                    subAgentKeys = listOf("leaf"),
                    toolKeys = listOf("web_search")
                )
            ),
            knownModelKeys = setOf("claude")
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    // --- filename / key ---

    @Test
    fun `filename mismatch is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            mcpServers = listOf(mcp(path = "mcp-servers/wrong-name.md", key = "context7"))
        )
        assertSingleError(snapshot, "mcp-servers/wrong-name.md") { msg ->
            msg.contains("filename key 'wrong-name'") && msg.contains("'context7'")
        }
    }

    @Test
    fun `nested directory path is rejected as malformed`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(agent(path = "agents/sub/foo.md", key = "foo", modelKey = "m", promptKey = "p")),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "agents/sub/foo.md" && it.message.contains("invalid path") })
    }

    @Test
    fun `wrong directory path is rejected as malformed`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(path = "agents/t.md", key = "t", scriptKey = "s")),
            knownScriptKeys = setOf("s")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "agents/t.md" && it.message.contains("invalid path") && it.message.contains("'tools/") })
    }

    @Test
    fun `non-md extension is rejected as malformed`() {
        val snapshot = AgentRepoTreeSnapshot(
            prompts = listOf(prompt(path = "prompts/p.txt", key = "p"))
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "prompts/p.txt" && it.message.contains("invalid path") })
    }

    // --- duplicates ---

    @Test
    fun `duplicate key in same directory is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(
                tool(path = "tools/a.md", key = "a", scriptKey = "s"),
                tool(path = "tools/a-copy.md", key = "a", scriptKey = "s")
            ),
            knownScriptKeys = setOf("s")
        )
        // The duplicate is reported at the second file's path; the first file may also
        // emit a filename-mismatch since path 'tools/a-copy.md' != key 'a'.
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "tools/a-copy.md" && it.message.contains("duplicate key 'a'") })
    }

    // --- tool XOR / references ---

    @Test
    fun `tool with both mcp_server and script is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            mcpServers = listOf(mcp(key = "m")),
            tools = listOf(tool(key = "t", mcpServerKey = "m", scriptKey = "s")),
            knownScriptKeys = setOf("s")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any {
            it.message.contains("sets multiple implementation variants") &&
                it.message.contains("mcp_server") && it.message.contains("script")
        })
    }

    @Test
    fun `tool with neither mcp_server nor script is allowed`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(key = "t"))
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `tool referencing unknown mcp_server is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(key = "t", mcpServerKey = "missing"))
        )
        assertSingleError(snapshot, "tools/t.md") { it.contains("unknown mcp_server 'missing'") }
    }

    @Test
    fun `tool referencing unknown script is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(key = "t", scriptKey = "missing"))
        )
        assertSingleError(snapshot, "tools/t.md") { it.contains("unknown script 'missing'") }
    }

    @Test
    fun `tool mcp_server resolved from DB-only known set`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(key = "t", mcpServerKey = "db-only")),
            knownMcpServerKeys = setOf("db-only")
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    // --- agent references ---

    @Test
    fun `agent referencing unknown model is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            prompts = listOf(prompt(key = "p")),
            agents = listOf(agent(key = "a", modelKey = "missing", promptKey = "p"))
        )
        assertSingleError(snapshot, "agents/a.md") { it.contains("unknown model 'missing'") }
    }

    @Test
    fun `agent referencing unknown prompt is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(agent(key = "a", modelKey = "m", promptKey = "missing")),
            knownModelKeys = setOf("m")
        )
        assertSingleError(snapshot, "agents/a.md") { it.contains("unknown prompt 'missing'") }
    }

    @Test
    fun `agent prompt resolved from in-tree wins`() {
        val snapshot = AgentRepoTreeSnapshot(
            prompts = listOf(prompt(key = "p")),
            agents = listOf(agent(key = "a", modelKey = "m", promptKey = "p")),
            knownModelKeys = setOf("m")
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `agent prompt resolved from DB-only known set`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(agent(key = "a", modelKey = "m", promptKey = "db-prompt")),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("db-prompt")
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `agent referencing unknown sub_agent is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("missing"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        assertSingleError(snapshot, "agents/a.md") { it.contains("unknown sub_agent 'missing'") }
    }

    @Test
    fun `agent referencing unknown tool is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", toolKeys = listOf("missing"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        assertSingleError(snapshot, "agents/a.md") { it.contains("unknown tool 'missing'") }
    }

    // --- resource XOR / references ---

    @Test
    fun `resource with a single variant is Ok`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", staticText = "x"))
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `resource with no variant is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r"))
        )
        assertSingleError(snapshot, "resources/r.md") { it.contains("sets no implementation variant") }
    }

    @Test
    fun `resource with multiple variants is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", staticText = "x", metadataSlug = "the-doc"))
        )
        assertSingleError(snapshot, "resources/r.md") {
            it.contains("sets multiple implementation variants") &&
                it.contains("static_text") && it.contains("metadata")
        }
    }

    @Test
    fun `resource referencing unknown script is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", scriptKey = "missing"))
        )
        assertSingleError(snapshot, "resources/r.md") { it.contains("unknown script 'missing'") }
    }

    @Test
    fun `resource with known script is Ok`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", scriptKey = "calc")),
            knownScriptKeys = setOf("calc")
        )
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `resource with document_version but no document_metadata is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", staticText = "x", documentVersion = 2))
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "resources/r.md" && it.message.contains("document_version without document_metadata") })
    }

    @Test
    fun `resource with graphql transform but no operation is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(key = "r", staticText = "x", graphqlInputTransform = "in"))
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "resources/r.md" && it.message.contains("transform without graphql_operation") })
    }

    @Test
    fun `resource filename mismatch is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(resource(path = "resources/wrong.md", key = "r", staticText = "x"))
        )
        assertSingleError(snapshot, "resources/wrong.md") { it.contains("filename key 'wrong'") && it.contains("'r'") }
    }

    @Test
    fun `duplicate resource key is an error`() {
        val snapshot = AgentRepoTreeSnapshot(
            resources = listOf(
                resource(path = "resources/a.md", key = "a", staticText = "x"),
                resource(path = "resources/a-copy.md", key = "a", staticText = "y")
            )
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.path == "resources/a-copy.md" && it.message.contains("duplicate key 'a'") })
    }

    // --- sub_agent cycles ---

    @Test
    fun `self-cycle is detected`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("a"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.message.contains("sub_agent cycle detected") && it.message.contains("a -> a") })
    }

    @Test
    fun `two-cycle is detected`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("b")),
                agent(key = "b", modelKey = "m", promptKey = "p", subAgentKeys = listOf("a"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.message.contains("sub_agent cycle detected") })
    }

    @Test
    fun `three-cycle is detected`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("b")),
                agent(key = "b", modelKey = "m", promptKey = "p", subAgentKeys = listOf("c")),
                agent(key = "c", modelKey = "m", promptKey = "p", subAgentKeys = listOf("a"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.message.contains("sub_agent cycle detected") })
    }

    @Test
    fun `cycle is reported only once`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("b")),
                agent(key = "b", modelKey = "m", promptKey = "p", subAgentKeys = listOf("a"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p")
        )
        val errors = errorsOf(snapshot)
        val cycleErrors = errors.filter { it.message.contains("sub_agent cycle detected") }
        assertEquals(1, cycleErrors.size, "expected exactly one cycle error, got: $cycleErrors")
    }

    @Test
    fun `cycle via DB-only sub_agent is not detected (v1 limitation)`() {
        val snapshot = AgentRepoTreeSnapshot(
            agents = listOf(
                agent(key = "a", modelKey = "m", promptKey = "p", subAgentKeys = listOf("db-only"))
            ),
            knownModelKeys = setOf("m"),
            knownPromptKeys = setOf("p"),
            knownSubAgentKeys = setOf("db-only")
        )
        // db-only references would close the loop, but the validator doesn't see the
        // DB graph and treats the leaf as terminal.
        assertEquals(ValidationResult.Ok, validator.validate(snapshot))
    }

    @Test
    fun `multiple independent errors are all reported`() {
        val snapshot = AgentRepoTreeSnapshot(
            tools = listOf(tool(key = "t", mcpServerKey = "x", scriptKey = "y")),
            agents = listOf(agent(key = "a", modelKey = "missing-model", promptKey = "missing-prompt")),
            knownScriptKeys = setOf("y")
        )
        val errors = errorsOf(snapshot)
        assertTrue(errors.any { it.message.contains("sets multiple implementation variants") })
        assertTrue(errors.any { it.message.contains("unknown mcp_server 'x'") })
        assertTrue(errors.any { it.message.contains("unknown model 'missing-model'") })
        assertTrue(errors.any { it.message.contains("unknown prompt 'missing-prompt'") })
    }


    // --- helpers ---

    private fun assertSingleError(snapshot: AgentRepoTreeSnapshot, expectedPath: String, predicate: (String) -> Boolean) {
        val errors = errorsOf(snapshot)
        assertEquals(1, errors.size, "expected exactly one error, got: $errors")
        val err = errors.single()
        assertEquals(expectedPath, err.path)
        assertTrue(predicate(err.message), "message did not match: ${err.message}")
    }

    private fun errorsOf(snapshot: AgentRepoTreeSnapshot): List<RepoValidationError> {
        val result = validator.validate(snapshot)
        if (result is ValidationResult.Ok) fail("expected errors, got Ok")
        return assertIs<ValidationResult.Errors>(result).errors
    }

    private fun agent(
        path: String? = null,
        key: String,
        modelKey: String,
        promptKey: String,
        subAgentKeys: List<String> = emptyList(),
        toolKeys: List<String> = emptyList()
    ) = AgentFile(
        path = path ?: "agents/$key.md",
        key = key,
        name = key,
        description = "",
        modelKey = modelKey,
        promptKey = promptKey,
        subAgentKeys = subAgentKeys,
        toolKeys = toolKeys
    )

    private fun tool(
        path: String? = null,
        key: String,
        mcpServerKey: String? = null,
        scriptKey: String? = null
    ) = ToolFile(
        path = path ?: "tools/$key.md",
        key = key,
        name = key,
        description = "",
        mcpServerKey = mcpServerKey,
        scriptKey = scriptKey
    )

    private fun mcp(
        path: String? = null,
        key: String,
        transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP
    ) = McpServerFile(
        path = path ?: "mcp-servers/$key.md",
        key = key,
        name = key,
        description = "",
        transportType = transportType,
        configuration = JsonObject(emptyMap())
    )

    private fun resource(
        path: String? = null,
        key: String,
        staticText: String? = null,
        metadataSlug: String? = null,
        documentMetadataSlug: String? = null,
        documentVersion: Int? = null,
        contentMetadataSlug: String? = null,
        scriptKey: String? = null,
        graphqlOperation: String? = null,
        graphqlInputTransform: String? = null,
        graphqlOutputTransform: String? = null
    ) = ResourceFile(
        path = path ?: "resources/$key.md",
        key = key,
        name = key,
        description = "",
        staticText = staticText,
        metadataSlug = metadataSlug,
        documentMetadataSlug = documentMetadataSlug,
        documentVersion = documentVersion,
        contentMetadataSlug = contentMetadataSlug,
        scriptKey = scriptKey,
        graphqlOperation = graphqlOperation,
        graphqlInputTransform = graphqlInputTransform,
        graphqlOutputTransform = graphqlOutputTransform
    )

    private fun prompt(
        path: String? = null,
        key: String
    ) = PromptFile(
        path = path ?: "prompts/$key.md",
        key = key,
        name = key,
        description = "",
        inputType = "text/plain",
        outputType = "text/plain",
        systemPrompt = "You are a test prompt.",
        userPrompt = "Do the thing."
    )
}
