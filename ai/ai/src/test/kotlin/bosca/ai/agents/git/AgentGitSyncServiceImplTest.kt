@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentInput
import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpTransportType
import bosca.ai.agents.service.AgentResourceService
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.models.model.Model
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.model.PromptInput
import bosca.ai.prompts.service.PromptService
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.git.model.Blob
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.git.service.CommitFileInput
import bosca.git.service.CommitFileResult
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryWriteService
import bosca.scripting.model.Script
import bosca.scripting.service.ScriptService
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AgentGitSyncServiceImplTest {

    private val agentService = mockk<AgentService>(relaxed = true)
    private val agentToolService = mockk<AgentToolService>(relaxed = true)
    private val mcpServerService = mockk<McpServerRegistrationService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)
    private val modelService = mockk<ModelService>(relaxed = true)
    private val scriptService = mockk<ScriptService>(relaxed = true)
    private val agentResourceService = mockk<AgentResourceService>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val writeService = mockk<RepositoryWriteService>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>(relaxed = true)

    private val sync = AgentGitSyncServiceImpl(
        agentService, agentToolService, mcpServerService, promptService,
        modelService, scriptService, agentResourceService, slugService,
        writeService, browseService
    )

    private val repoId = Uuid.random()

    // --- pushToGit ---

    @Test
    fun `pushToGit AGENT happy path returns Ok with commit sha`() = runTest {
        val agentId = Uuid.random()
        val modelId = Uuid.random()
        val promptId = Uuid.random()
        val gitPath = "agents/research.md"
        val agent = Agent(
            id = agentId, key = "research", name = "Research", description = "desc",
            modelId = modelId, promptId = promptId,
            gitRepositoryId = repoId, gitPath = gitPath
        )
        coEvery { agentService.get(agentId) } returns agent
        coEvery { modelService.get(modelId) } returns Model(id = modelId, key = "claude", type = "anthropic", name = "C", description = "")
        coEvery { promptService.get(promptId) } returns prompt(promptId, "research-sys")
        coEvery { agentService.getSubAgents(agentId) } returns emptyList()
        coEvery { agentService.getTools(agentId) } returns emptyList()
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "abc123", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.AGENT, agentId, "Tester", "test@example.com")

        assertEquals(SyncResult.Ok(commitSha = "abc123"), result)
        assertEquals(repoId, inputSlot.captured.repositoryId)
        assertEquals(gitPath, inputSlot.captured.path)
        assertTrue(inputSlot.captured.content.contains("key: research"))
        assertTrue(inputSlot.captured.content.contains("model: claude"))
        coVerify { agentService.setSyncError(agentId, null) }
    }

    @Test
    fun `pushToGit returns Failure when agent has no gitRepositoryId`() = runTest {
        val id = Uuid.random()
        coEvery { agentService.get(id) } returns Agent(
            id = id, key = "a", name = "A", description = "",
            modelId = Uuid.random(), promptId = Uuid.random()
        )

        val result = sync.pushToGit(AgentEntityType.AGENT, id, "Tester", "t@e.com")

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("not linked"))
    }

    @Test
    fun `pushToGit returns Failure when agent not found`() = runTest {
        val id = Uuid.random()
        coEvery { agentService.get(id) } returns null

        val result = sync.pushToGit(AgentEntityType.AGENT, id, "Tester", "t@e.com")

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("not found"))
    }

    @Test
    fun `pushToGit AGENT_TOOL happy path serializes tool to git`() = runTest {
        val toolId = Uuid.random()
        val mcpServerId = Uuid.random()
        val gitPath = "tools/web_search.md"
        coEvery { agentToolService.get(toolId) } returns AgentTool(
            id = toolId, key = "web_search", name = "Web Search", description = "Searches the web",
            mcpServerId = mcpServerId,
            gitRepositoryId = repoId, gitPath = gitPath
        )
        coEvery { mcpServerService.get(mcpServerId) } returns mcp(mcpServerId, "context7")
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "abc", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.AGENT_TOOL, toolId, "T", "t@e.com")

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        assertTrue(inputSlot.captured.content.contains("mcp_server: context7"))
        coVerify { agentToolService.setSyncError(toolId, null) }
    }

    @Test
    fun `pushToGit MCP_SERVER happy path serializes server to git`() = runTest {
        val serverId = Uuid.random()
        val gitPath = "mcp-servers/context7.md"
        coEvery { mcpServerService.get(serverId) } returns mcp(serverId, "context7").copy(
            gitRepositoryId = repoId, gitPath = gitPath
        )
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "abc", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.MCP_SERVER, serverId, "T", "t@e.com")

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        assertTrue(inputSlot.captured.content.contains("transport_type: STREAMABLE_HTTP"))
        coVerify { mcpServerService.setSyncError(serverId, null) }
    }

    @Test
    fun `pushToGit PROMPT happy path serializes prompt to git`() = runTest {
        val promptId = Uuid.random()
        val gitPath = "prompts/research.md"
        coEvery { promptService.get(promptId) } returns Prompt(
            id = promptId, key = "research", name = "Research", description = "d",
            systemPrompt = "You are.", userPrompt = "Do.",
            inputType = "text/plain", outputType = "text/plain", schema = null,
            gitRepositoryId = repoId, gitPath = gitPath
        )
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "abc", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.PROMPT, promptId, "T", "t@e.com")

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        assertTrue(inputSlot.captured.content.contains("## System Prompt"))
        assertTrue(inputSlot.captured.content.contains("## User Prompt"))
        coVerify { promptService.setSyncError(promptId, null) }
    }

    @Test
    fun `pushAgentTool records sync error on dangling MCP server FK`() = runTest {
        val toolId = Uuid.random()
        val mcpServerId = Uuid.random()
        coEvery { agentToolService.get(toolId) } returns AgentTool(
            id = toolId, key = "t", name = "T", description = "",
            mcpServerId = mcpServerId,
            gitRepositoryId = repoId, gitPath = "tools/t.md"
        )
        coEvery { mcpServerService.get(mcpServerId) } returns null

        val result = sync.pushToGit(AgentEntityType.AGENT_TOOL, toolId, "T", "t@e.com")

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("MCP server FK") && fail.message.contains("dangling"))
        coVerify { agentToolService.setSyncError(toolId, match { it.contains("dangling") }) }
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    @Test
    fun `pushAgent records sync error and returns Failure when commitFile throws`() = runTest {
        val agentId = Uuid.random()
        val modelId = Uuid.random()
        val promptId = Uuid.random()
        coEvery { agentService.get(agentId) } returns Agent(
            id = agentId, key = "a", name = "A", description = "",
            modelId = modelId, promptId = promptId,
            gitRepositoryId = repoId, gitPath = "agents/a.md"
        )
        coEvery { modelService.get(modelId) } returns Model(id = modelId, key = "m", type = "anthropic", name = "M", description = "")
        coEvery { promptService.get(promptId) } returns prompt(promptId, "p")
        coEvery { agentService.getSubAgents(agentId) } returns emptyList()
        coEvery { agentService.getTools(agentId) } returns emptyList()
        coEvery { writeService.commitFile(any()) } throws RuntimeException("repo offline")

        val result = sync.pushToGit(AgentEntityType.AGENT, agentId, "T", "t@e.com")

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("repo offline"))
        coVerify { agentService.setSyncError(agentId, match { it.contains("repo offline") }) }
    }

    // --- onPushEvent ---

    @Test
    fun `onPushEvent skips pull when no relevant paths changed`() = runTest {
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("README.md", "docs/intro.md")

        sync.onPushEvent(repoId, "old", "new")

        coVerify(exactly = 0) { browseService.listTree(any(), any(), any()) }
    }

    @Test
    fun `onPushEvent triggers pull when an agent file changed`() = runTest {
        coEvery { browseService.listChangedPaths(repoId, "old", "new") } returns setOf("agents/foo.md")
        // Empty tree on the new commit — pullFromGit will succeed trivially.
        coEvery { browseService.listTree(repoId, "new", any()) } returns emptyList()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        withContext(connectionManager.asCoroutineContext()) {
            sync.onPushEvent(repoId, "old", "new")
        }

        coVerify(atLeast = 1) { browseService.listTree(repoId, "new", any()) }
    }

    // --- pullFromGit ---

    @Test
    fun `pullFromGit on empty tree returns Ok`() = runTest {
        coEvery { browseService.listTree(repoId, "abc", any()) } returns emptyList()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
    }

    @Test
    fun `pullFromGit returns ValidationFailed on parse error`() = runTest {
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.AGENTS_DIR) } returns listOf(
            TreeEntry(name = "broken.md", path = "agents/broken.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.TOOLS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.MCP_SERVERS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.PROMPTS_DIR) } returns emptyList()
        coEvery { browseService.readBlob(repoId, "abc", "agents/broken.md") } returns
            Blob(content = "no frontmatter here", size = 20, sha = "x")

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        val failed = assertIs<SyncResult.ValidationFailed>(result)
        assertTrue(failed.errors.any { it.path == "agents/broken.md" })
    }

    @Test
    fun `pullFromGit returns ValidationFailed on validator errors`() = runTest {
        val content = """
            ---
            key: a
            name: A
            model: missing-model
            prompt: missing-prompt
            ---
        """.trimIndent()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.AGENTS_DIR) } returns listOf(
            TreeEntry(name = "a.md", path = "agents/a.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.TOOLS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.MCP_SERVERS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.PROMPTS_DIR) } returns emptyList()
        coEvery { browseService.readBlob(repoId, "abc", "agents/a.md") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        val failed = assertIs<SyncResult.ValidationFailed>(result)
        assertTrue(failed.errors.any { it.message.contains("unknown model") })
        assertTrue(failed.errors.any { it.message.contains("unknown prompt") })
    }

    @Test
    fun `pullFromGit edits existing agent when found by gitPath`() = runTest {
        val existingId = Uuid.random()
        val modelId = Uuid.random()
        val promptId = Uuid.random()
        val gitPath = "agents/research.md"
        val content = """
            ---
            key: research
            name: Research
            model: claude
            prompt: research-sys
            ---

            Updated description.
        """.trimIndent()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.AGENTS_DIR) } returns listOf(
            TreeEntry(name = "research.md", path = gitPath, type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.TOOLS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.MCP_SERVERS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.PROMPTS_DIR) } returns emptyList()
        coEvery { browseService.readBlob(repoId, "abc", gitPath) } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        val existingAgent = Agent(
            id = existingId, key = "research", name = "Research (old)", description = "old",
            modelId = modelId, promptId = promptId,
            gitRepositoryId = repoId, gitPath = gitPath
        )
        coEvery { modelService.getKeyIndex() } returns mapOf("claude" to modelId)
        coEvery { promptService.getKeyIndex() } returns mapOf("research-sys" to promptId)
        coEvery { agentService.getKeyIndex() } returns mapOf("research" to existingId)
        coEvery { agentService.getByGitRepository(repoId, gitPath) } returns existingAgent
        val editSlot = slot<AgentInput>()
        val editedAgent = existingAgent.copy(name = "Research", description = "Updated description.")
        coEvery { agentService.edit(existingId, capture(editSlot)) } returns editedAgent

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        assertEquals("research", editSlot.captured.key)
        assertEquals("Research", editSlot.captured.name)
        assertEquals("Updated description.", editSlot.captured.description)
        assertEquals(repoId, editSlot.captured.gitRepositoryId)
        coVerify(exactly = 0) { agentService.add(any()) }
        coVerify { agentService.setSyncError(existingId, null) }
    }

    @Test
    fun `pullFromGit upserts new agent when validator passes`() = runTest {
        val content = """
            ---
            key: research
            name: Research
            model: claude
            prompt: research-sys
            ---

            Description body.
        """.trimIndent()
        val modelId = Uuid.random()
        val promptId = Uuid.random()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.AGENTS_DIR) } returns listOf(
            TreeEntry(name = "research.md", path = "agents/research.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.TOOLS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.MCP_SERVERS_DIR) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.PROMPTS_DIR) } returns emptyList()
        coEvery { browseService.readBlob(repoId, "abc", "agents/research.md") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { modelService.getKeyIndex() } returns mapOf("claude" to modelId)
        coEvery { promptService.getKeyIndex() } returns mapOf("research-sys" to promptId)
        coEvery { agentService.getByGitRepository(repoId, "agents/research.md") } returns null
        val addedSlot = slot<AgentInput>()
        val newAgent = Agent(
            id = Uuid.random(), key = "research", name = "Research", description = "Description body.",
            modelId = modelId, promptId = promptId,
            gitRepositoryId = repoId, gitPath = "agents/research.md"
        )
        coEvery { agentService.add(capture(addedSlot)) } returns newAgent

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        val captured = addedSlot.captured
        assertEquals("research", captured.key)
        assertEquals(modelId, captured.modelId)
        assertEquals(promptId, captured.promptId)
        assertEquals(repoId, captured.gitRepositoryId)
        assertEquals("agents/research.md", captured.gitPath)
        coVerify { agentService.setSubAgents(newAgent.id, emptyList()) }
        coVerify { agentService.setTools(newAgent.id, emptyList()) }
    }

    // --- pushToGit AGENT_RESOURCE ---

    @Test
    fun `pushToGit AGENT_RESOURCE static text happy path serializes resource to git`() = runTest {
        val resourceId = Uuid.random()
        val gitPath = "resources/welcome.md"
        coEvery { agentResourceService.get(resourceId) } returns AgentResource(
            id = resourceId, key = "welcome", name = "Welcome", description = "A greeting",
            staticText = "Hello there",
            gitRepositoryId = repoId, gitPath = gitPath
        )
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "r1", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.AGENT_RESOURCE, resourceId, "T", "t@e.com")

        assertEquals(SyncResult.Ok(commitSha = "r1"), result)
        assertTrue(inputSlot.captured.content.contains("key: welcome"))
        assertTrue(inputSlot.captured.content.contains("static_text: Hello there"))
        coVerify { agentResourceService.setSyncError(resourceId, null) }
    }

    @Test
    fun `pushToGit AGENT_RESOURCE resolves metadata id to slug`() = runTest {
        val resourceId = Uuid.random()
        val metadataId = Uuid.random()
        val gitPath = "resources/article.md"
        coEvery { agentResourceService.get(resourceId) } returns AgentResource(
            id = resourceId, key = "article", name = "Article", description = "An article",
            metadataId = metadataId,
            gitRepositoryId = repoId, gitPath = gitPath
        )
        coEvery { slugService.getMetadataSlug(metadataId) } returns "my-article"
        val inputSlot = slot<CommitFileInput>()
        coEvery { writeService.commitFile(capture(inputSlot)) } returns CommitFileResult(commitSha = "r2", branch = "main", path = gitPath)

        val result = sync.pushToGit(AgentEntityType.AGENT_RESOURCE, resourceId, "T", "t@e.com")

        assertEquals(SyncResult.Ok(commitSha = "r2"), result)
        assertTrue(inputSlot.captured.content.contains("metadata: my-article"))
        coVerify { agentResourceService.setSyncError(resourceId, null) }
    }

    @Test
    fun `pushAgentResource records sync error when metadata has no slug`() = runTest {
        val resourceId = Uuid.random()
        val metadataId = Uuid.random()
        coEvery { agentResourceService.get(resourceId) } returns AgentResource(
            id = resourceId, key = "article", name = "Article", description = "",
            metadataId = metadataId,
            gitRepositoryId = repoId, gitPath = "resources/article.md"
        )
        coEvery { slugService.getMetadataSlug(metadataId) } returns null

        val result = sync.pushToGit(AgentEntityType.AGENT_RESOURCE, resourceId, "T", "t@e.com")

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("no slug"))
        coVerify { agentResourceService.setSyncError(resourceId, match { it.contains("no slug") }) }
        coVerify(exactly = 0) { writeService.commitFile(any()) }
    }

    // --- pullFromGit AGENT_RESOURCE ---

    @Test
    fun `pullFromGit upserts new static-text resource`() = runTest {
        val content = """
            ---
            key: welcome
            name: Welcome
            static_text: Hello
            ---

            A greeting resource.
        """.trimIndent()
        coEvery { browseService.listTree(repoId, "abc", any()) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.RESOURCES_DIR) } returns listOf(
            TreeEntry(name = "welcome.md", path = "resources/welcome.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "resources/welcome.md") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { agentResourceService.getByGitRepository(repoId, "resources/welcome.md") } returns null
        val addedSlot = slot<AgentResourceInput>()
        val newResource = AgentResource(
            id = Uuid.random(), key = "welcome", name = "Welcome", description = "A greeting resource.",
            staticText = "Hello", gitRepositoryId = repoId, gitPath = "resources/welcome.md"
        )
        coEvery { agentResourceService.add(capture(addedSlot)) } returns newResource

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        val captured = addedSlot.captured
        assertEquals("welcome", captured.key)
        assertEquals("Hello", captured.staticText)
        assertEquals("A greeting resource.", captured.description)
        assertEquals(repoId, captured.gitRepositoryId)
        coVerify { agentResourceService.setSyncError(newResource.id, null) }
    }

    @Test
    fun `pullFromGit returns ValidationFailed on unknown metadata slug`() = runTest {
        val content = """
            ---
            key: article
            name: Article
            metadata: ghost-slug
            ---

            An article resource.
        """.trimIndent()
        coEvery { browseService.listTree(repoId, "abc", any()) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.RESOURCES_DIR) } returns listOf(
            TreeEntry(name = "article.md", path = "resources/article.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "resources/article.md") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { slugService.get("ghost-slug") } returns null

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        val failed = assertIs<SyncResult.ValidationFailed>(result)
        assertTrue(failed.errors.any { it.message.contains("unknown metadata slug") && it.message.contains("ghost-slug") })
        coVerify(exactly = 0) { agentResourceService.add(any()) }
    }

    @Test
    fun `pullFromGit resolves metadata slug to id when adding resource`() = runTest {
        val metadataId = Uuid.random()
        val content = """
            ---
            key: article
            name: Article
            metadata: my-article
            ---

            An article resource.
        """.trimIndent()
        coEvery { browseService.listTree(repoId, "abc", any()) } returns emptyList()
        coEvery { browseService.listTree(repoId, "abc", AgentRepoLayout.RESOURCES_DIR) } returns listOf(
            TreeEntry(name = "article.md", path = "resources/article.md", type = TreeEntryType.BLOB, mode = 0, sha = "x")
        )
        coEvery { browseService.readBlob(repoId, "abc", "resources/article.md") } returns
            Blob(content = content, size = content.length.toLong(), sha = "x")
        coEvery { slugService.get("my-article") } returns Slug(slug = "my-article", metadataId = metadataId)
        coEvery { agentResourceService.getByGitRepository(repoId, "resources/article.md") } returns null
        val addedSlot = slot<AgentResourceInput>()
        val newResource = AgentResource(
            id = Uuid.random(), key = "article", name = "Article", description = "An article resource.",
            metadataId = metadataId, gitRepositoryId = repoId, gitPath = "resources/article.md"
        )
        coEvery { agentResourceService.add(capture(addedSlot)) } returns newResource

        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val result = withContext(connectionManager.asCoroutineContext()) {
            sync.pullFromGit(repoId, "abc")
        }

        assertEquals(SyncResult.Ok(commitSha = "abc"), result)
        assertEquals(metadataId, addedSlot.captured.metadataId)
    }

    // --- backfill ---

    @Test
    fun `backfill links and pushes each entry`() = runTest {
        val agentId = Uuid.random()
        val modelId = Uuid.random()
        val promptId = Uuid.random()
        // After linkToGit, the row reflects the new git linkage.
        coEvery { agentService.get(agentId) } returns Agent(
            id = agentId, key = "a", name = "A", description = "",
            modelId = modelId, promptId = promptId,
            gitRepositoryId = repoId, gitPath = "agents/a.md"
        )
        coEvery { modelService.get(modelId) } returns Model(id = modelId, key = "m", type = "anthropic", name = "M", description = "")
        coEvery { promptService.get(promptId) } returns prompt(promptId, "p")
        coEvery { agentService.getSubAgents(agentId) } returns emptyList()
        coEvery { agentService.getTools(agentId) } returns emptyList()
        coEvery { writeService.commitFile(any()) } returns CommitFileResult(commitSha = "sha1", branch = "main", path = "agents/a.md")

        val result = sync.backfill(
            repoId,
            listOf(BackfillEntry(AgentEntityType.AGENT, agentId, "agents/a.md")),
            "T", "t@e.com"
        )

        assertEquals(SyncResult.Ok(commitSha = "sha1"), result)
        coVerify { agentService.linkToGit(agentId, repoId, "agents/a.md") }
        coVerify { writeService.commitFile(any()) }
    }

    @Test
    fun `backfill collects errors from failed entries`() = runTest {
        val id = Uuid.random()
        coEvery { agentService.get(id) } returns null

        val result = sync.backfill(
            repoId,
            listOf(BackfillEntry(AgentEntityType.AGENT, id, "agents/missing.md")),
            "T", "t@e.com"
        )

        val fail = assertIs<SyncResult.Failure>(result)
        assertTrue(fail.message.contains("agents/missing.md"))
    }

    // --- helpers ---

    private fun prompt(id: Uuid, key: String) = Prompt(
        id = id, key = key, name = key, description = "",
        systemPrompt = "S", userPrompt = "U",
        inputType = "text/plain", outputType = "text/plain",
        schema = null
    )

    private fun mcp(id: Uuid, key: String) = McpServerRegistration(
        id = id, key = key, name = key,
        transportType = McpTransportType.STREAMABLE_HTTP,
        configuration = JsonObject(emptyMap())
    )
}
