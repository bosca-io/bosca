@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.git.model.RepositoryContentType
import bosca.scripting.service.ScriptService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AgentProjectContentValidatorTest {

    private val agentService = mockk<AgentService>(relaxed = true)
    private val agentToolService = mockk<AgentToolService>(relaxed = true)
    private val mcpServerService = mockk<McpServerRegistrationService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)
    private val modelService = mockk<ModelService>(relaxed = true)
    private val scriptService = mockk<ScriptService>(relaxed = true)

    private val validator = AgentProjectContentValidator(
        agentService, agentToolService, mcpServerService,
        promptService, modelService, scriptService,
    )

    private val repoId = Uuid.random()

    @Test
    fun `contentType is AGENT_PROJECT`() {
        assertEquals(RepositoryContentType.AGENT_PROJECT, validator.contentType)
    }

    @Test
    fun `pathPrefixes are the AGENT_PROJECT directories`() {
        assertEquals(
            listOf("agents/", "tools/", "mcp-servers/", "prompts/", "resources/"),
            validator.pathPrefixes,
        )
    }

    @Test
    fun `empty files map returns no errors`() = runTest {
        val errors = validator.validate(repoId, emptyMap())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `valid agent with resolvable model and prompt returns no errors`() = runTest {
        coEvery { modelService.getKeyIndex() } returns mapOf("claude" to Uuid.random())
        coEvery { promptService.getKeyIndex() } returns mapOf("research-sys" to Uuid.random())

        val files = mapOf(
            "agents/research.md" to """
                ---
                key: research
                name: Research
                model: claude
                prompt: research-sys
                ---
                Description.
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
    }

    @Test
    fun `agent referencing unknown model is rejected`() = runTest {
        coEvery { promptService.getKeyIndex() } returns mapOf("p" to Uuid.random())

        val files = mapOf(
            "agents/a.md" to """
                ---
                key: a
                name: A
                model: missing-model
                prompt: p
                ---
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.any { it.message.contains("unknown model 'missing-model'") })
    }

    @Test
    fun `file with malformed frontmatter is reported as a parse error`() = runTest {
        val files = mapOf("agents/broken.md" to "no frontmatter here\n")
        val errors = validator.validate(repoId, files)
        assertEquals(1, errors.size)
        assertEquals("agents/broken.md", errors[0].path)
        assertTrue(errors[0].message.contains("must start with YAML frontmatter"))
    }

    @Test
    fun `parse errors short-circuit before validator runs`() = runTest {
        // Two files: one parse error, one would-trigger validator error.
        // Expect ONLY the parse error since validation aborts.
        val files = mapOf(
            "agents/broken.md" to "no frontmatter",
            "agents/a.md" to """
                ---
                key: a
                name: A
                model: missing-model
                prompt: missing-prompt
                ---
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.any { it.path == "agents/broken.md" })
        assertTrue(errors.none { it.message.contains("unknown model") }, "validator should not run when parse errors exist")
    }

    @Test
    fun `prompt with valid System and User Prompt sections is accepted`() = runTest {
        val files = mapOf(
            "prompts/p.md" to """
                ---
                key: p
                name: P
                description: A prompt
                input_type: text/plain
                output_type: text/plain
                ---

                ## System Prompt

                You are.

                ## User Prompt

                Do.
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
    }

    @Test
    fun `tool with both mcp_server and script set is rejected`() = runTest {
        coEvery { mcpServerService.getKeyIndex() } returns mapOf("m" to Uuid.random())
        coEvery { scriptService.getKeyIndex() } returns mapOf("s" to Uuid.random())

        val files = mapOf(
            "tools/t.md" to """
                ---
                key: t
                name: T
                mcp_server: m
                script: s
                ---
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.any { it.message.contains("sets multiple implementation variants") })
    }

    @Test
    fun `resource with no implementation variant is rejected`() = runTest {
        val files = mapOf(
            "resources/r.md" to """
                ---
                key: r
                name: R
                ---
                A resource with no variant.
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.any { it.path == "resources/r.md" && it.message.contains("sets no implementation variant") })
    }

    @Test
    fun `resource with static text is accepted`() = runTest {
        val files = mapOf(
            "resources/welcome.md" to """
                ---
                key: welcome
                name: Welcome
                static_text: Hello there
                ---
                A greeting.
            """.trimIndent()
        )
        val errors = validator.validate(repoId, files)
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
    }
}
