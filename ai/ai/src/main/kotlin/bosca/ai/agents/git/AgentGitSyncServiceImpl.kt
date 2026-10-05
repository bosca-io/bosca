@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.ai.agents.git.AgentRepoLayout.AGENTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.MCP_SERVERS_DIR
import bosca.ai.agents.git.AgentRepoLayout.PROMPTS_DIR
import bosca.ai.agents.git.AgentRepoLayout.RESOURCES_DIR
import bosca.ai.agents.git.AgentRepoLayout.TOOLS_DIR
import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentInput
import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.model.AgentToolInput
import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.ai.agents.service.AgentResourceService
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.model.PromptInput
import bosca.ai.prompts.service.PromptService
import bosca.db.transaction
import bosca.git.model.TreeEntryType
import bosca.git.service.CommitFileInput
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryWriteService
import bosca.scripting.service.ScriptService
import bosca.service.annotation.ServiceImplementation
import bosca.slug.service.SlugService
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Bidirectional sync between PostgreSQL rows and Markdown files in an AGENT_PROJECT
 * repository. Push serializes a single DB row to its `git_path` and commits; pull
 * walks the four known directories at a commit, validates the whole tree, and upserts
 * rows in dependency order (MCP servers → tools → prompts → agents).
 */
@ServiceImplementation
class AgentGitSyncServiceImpl(
    private val agentService: AgentService,
    private val agentToolService: AgentToolService,
    private val mcpServerService: McpServerRegistrationService,
    private val promptService: PromptService,
    private val modelService: ModelService,
    private val scriptService: ScriptService,
    private val agentResourceService: AgentResourceService,
    private val slugService: SlugService,
    private val writeService: RepositoryWriteService,
    private val browseService: RepositoryBrowseService,
) : AgentGitSyncService {

    private val parser = AgentRepoFileParser()
    private val serializer = AgentRepoFileSerializer()
    private val validator: AgentRepoValidator = AgentRepoValidatorImpl()
    private val log = LoggerFactory.getLogger(AgentGitSyncServiceImpl::class.java)

    override suspend fun pushToGit(
        entityType: AgentEntityType,
        entityId: Uuid,
        authorName: String,
        authorEmail: String
    ): SyncResult = when (entityType) {
        AgentEntityType.AGENT -> pushAgent(entityId, authorName, authorEmail)
        AgentEntityType.AGENT_TOOL -> pushAgentTool(entityId, authorName, authorEmail)
        AgentEntityType.MCP_SERVER -> pushMcpServer(entityId, authorName, authorEmail)
        AgentEntityType.PROMPT -> pushPrompt(entityId, authorName, authorEmail)
        AgentEntityType.AGENT_RESOURCE -> pushAgentResource(entityId, authorName, authorEmail)
    }

    private suspend fun pushAgent(id: Uuid, authorName: String, authorEmail: String): SyncResult {
        val agent = agentService.get(id) ?: return SyncResult.Failure("Agent not found: $id")
        val repoId = agent.gitRepositoryId ?: return SyncResult.Failure("Agent '${agent.key}' is not linked to a Git repository")
        val gitPath = agent.gitPath ?: return SyncResult.Failure("Agent '${agent.key}' has no git_path")
        try {
            val model = modelService.get(agent.modelId)
                ?: return recordSyncError(AgentEntityType.AGENT, id, "Model FK ${agent.modelId} is dangling for agent '${agent.key}'")
            val prompt = promptService.get(agent.promptId)
                ?: return recordSyncError(AgentEntityType.AGENT, id, "Prompt FK ${agent.promptId} is dangling for agent '${agent.key}'")
            val subAgents = agentService.getSubAgents(id)
            val tools = agentService.getTools(id)
            val file = AgentFile(
                path = gitPath,
                key = agent.key,
                name = agent.name,
                description = agent.description,
                modelKey = model.key,
                promptKey = prompt.key,
                subAgentKeys = subAgents.map { it.key },
                toolKeys = tools.map { it.key },
                configuration = agent.configuration
            )
            val result = commit(repoId, gitPath, serializer.serializeAgent(file), "Update agent ${agent.key}", authorName, authorEmail)
            agentService.setSyncError(id, null)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(AgentEntityType.AGENT, id, "Failed to push agent '${agent.key}': ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun pushAgentTool(id: Uuid, authorName: String, authorEmail: String): SyncResult {
        val tool = agentToolService.get(id) ?: return SyncResult.Failure("AgentTool not found: $id")
        val repoId = tool.gitRepositoryId ?: return SyncResult.Failure("Tool '${tool.key}' is not linked to a Git repository")
        val gitPath = tool.gitPath ?: return SyncResult.Failure("Tool '${tool.key}' has no git_path")
        try {
            val toolMcpServerId = tool.mcpServerId
            val mcpServerKey = if (toolMcpServerId != null) {
                mcpServerService.get(toolMcpServerId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_TOOL, id, "MCP server FK $toolMcpServerId is dangling for tool '${tool.key}'")
            } else null
            val toolScriptId = tool.scriptId
            val scriptKey = if (toolScriptId != null) {
                scriptService.get(toolScriptId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_TOOL, id, "Script FK $toolScriptId is dangling for tool '${tool.key}'")
            } else null
            val toolPromptId = tool.promptId
            val promptKey = if (toolPromptId != null) {
                promptService.get(toolPromptId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_TOOL, id, "Prompt FK $toolPromptId is dangling for tool '${tool.key}'")
            } else null
            val toolModelId = tool.modelId
            val modelKey = if (toolModelId != null) {
                modelService.get(toolModelId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_TOOL, id, "Model FK $toolModelId is dangling for tool '${tool.key}'")
            } else null
            val toolAgentId = tool.agentId
            val agentKey = if (toolAgentId != null) {
                agentService.get(toolAgentId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_TOOL, id, "Agent FK $toolAgentId is dangling for tool '${tool.key}'")
            } else null
            val file = ToolFile(
                path = gitPath,
                key = tool.key,
                name = tool.name,
                description = tool.description,
                mcpServerKey = mcpServerKey,
                scriptKey = scriptKey,
                graphqlOperation = tool.graphqlOperation,
                graphqlInputTransform = tool.graphqlInputTransform,
                graphqlOutputTransform = tool.graphqlOutputTransform,
                promptKey = promptKey,
                modelKey = modelKey,
                agentKey = agentKey,
                configuration = tool.configuration
            )
            val result = commit(repoId, gitPath, serializer.serializeTool(file), "Update tool ${tool.key}", authorName, authorEmail)
            agentToolService.setSyncError(id, null)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(AgentEntityType.AGENT_TOOL, id, "Failed to push tool '${tool.key}': ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun pushMcpServer(id: Uuid, authorName: String, authorEmail: String): SyncResult {
        val server = mcpServerService.get(id) ?: return SyncResult.Failure("McpServerRegistration not found: $id")
        val repoId = server.gitRepositoryId ?: return SyncResult.Failure("MCP server '${server.key}' is not linked to a Git repository")
        val gitPath = server.gitPath ?: return SyncResult.Failure("MCP server '${server.key}' has no git_path")
        try {
            val file = McpServerFile(
                path = gitPath,
                key = server.key,
                name = server.name,
                description = server.description,
                transportType = server.transportType,
                configuration = server.configuration,
                enabled = server.enabled
            )
            val result = commit(repoId, gitPath, serializer.serializeMcpServer(file), "Update MCP server ${server.key}", authorName, authorEmail)
            mcpServerService.setSyncError(id, null)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(AgentEntityType.MCP_SERVER, id, "Failed to push MCP server '${server.key}': ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun pushPrompt(id: Uuid, authorName: String, authorEmail: String): SyncResult {
        val prompt = promptService.get(id) ?: return SyncResult.Failure("Prompt not found: $id")
        val repoId = prompt.gitRepositoryId ?: return SyncResult.Failure("Prompt '${prompt.key}' is not linked to a Git repository")
        val gitPath = prompt.gitPath ?: return SyncResult.Failure("Prompt '${prompt.key}' has no git_path")
        try {
            val file = PromptFile(
                path = gitPath,
                key = prompt.key,
                name = prompt.name,
                description = prompt.description,
                inputType = prompt.inputType,
                outputType = prompt.outputType,
                schema = prompt.schema,
                systemPrompt = prompt.systemPrompt,
                userPrompt = prompt.userPrompt
            )
            val result = commit(repoId, gitPath, serializer.serializePrompt(file), "Update prompt ${prompt.key}", authorName, authorEmail)
            promptService.setSyncError(id, null)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(AgentEntityType.PROMPT, id, "Failed to push prompt '${prompt.key}': ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun pushAgentResource(id: Uuid, authorName: String, authorEmail: String): SyncResult {
        val resource = agentResourceService.get(id) ?: return SyncResult.Failure("AgentResource not found: $id")
        val repoId = resource.gitRepositoryId ?: return SyncResult.Failure("Resource '${resource.key}' is not linked to a Git repository")
        val gitPath = resource.gitPath ?: return SyncResult.Failure("Resource '${resource.key}' has no git_path")
        try {
            // Metadata references are serialized as slugs so the repo stays human-readable and
            // diff-able; a missing slug means the metadata exists but was never assigned one.
            val resourceMetadataId = resource.metadataId
            val metadataSlug = if (resourceMetadataId != null) {
                slugService.getMetadataSlug(resourceMetadataId)
                    ?: return recordSyncError(AgentEntityType.AGENT_RESOURCE, id, "Metadata $resourceMetadataId has no slug for resource '${resource.key}'")
            } else null
            val resourceDocumentMetadataId = resource.documentMetadataId
            val documentMetadataSlug = if (resourceDocumentMetadataId != null) {
                slugService.getMetadataSlug(resourceDocumentMetadataId)
                    ?: return recordSyncError(AgentEntityType.AGENT_RESOURCE, id, "Document metadata $resourceDocumentMetadataId has no slug for resource '${resource.key}'")
            } else null
            val resourceContentMetadataId = resource.contentMetadataId
            val contentMetadataSlug = if (resourceContentMetadataId != null) {
                slugService.getMetadataSlug(resourceContentMetadataId)
                    ?: return recordSyncError(AgentEntityType.AGENT_RESOURCE, id, "Content metadata $resourceContentMetadataId has no slug for resource '${resource.key}'")
            } else null
            val resourceScriptId = resource.scriptId
            val scriptKey = if (resourceScriptId != null) {
                scriptService.get(resourceScriptId)?.key
                    ?: return recordSyncError(AgentEntityType.AGENT_RESOURCE, id, "Script FK $resourceScriptId is dangling for resource '${resource.key}'")
            } else null
            val file = ResourceFile(
                path = gitPath,
                key = resource.key,
                name = resource.name,
                description = resource.description,
                staticText = resource.staticText,
                metadataSlug = metadataSlug,
                documentMetadataSlug = documentMetadataSlug,
                documentVersion = resource.documentVersion,
                contentMetadataSlug = contentMetadataSlug,
                scriptKey = scriptKey,
                graphqlOperation = resource.graphqlOperation,
                graphqlInputTransform = resource.graphqlInputTransform,
                graphqlOutputTransform = resource.graphqlOutputTransform,
                configuration = resource.configuration
            )
            val result = commit(repoId, gitPath, serializer.serializeResource(file), "Update resource ${resource.key}", authorName, authorEmail)
            agentResourceService.setSyncError(id, null)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(AgentEntityType.AGENT_RESOURCE, id, "Failed to push resource '${resource.key}': ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun recordSyncError(entityType: AgentEntityType, id: Uuid, message: String): SyncResult.Failure {
        try {
            when (entityType) {
                AgentEntityType.AGENT -> agentService.setSyncError(id, message)
                AgentEntityType.AGENT_TOOL -> agentToolService.setSyncError(id, message)
                AgentEntityType.MCP_SERVER -> mcpServerService.setSyncError(id, message)
                AgentEntityType.PROMPT -> promptService.setSyncError(id, message)
                AgentEntityType.AGENT_RESOURCE -> agentResourceService.setSyncError(id, message)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The DB write failed; the underlying sync failure is still the thing the caller
            // needs to know about, so we return Failure(message) regardless. Log the inability
            // to persist so ops can see drift between SyncResult and last_sync_error.
            log.warn("Failed to persist sync error for {} {}: {}", entityType, id, e.message)
        }
        return SyncResult.Failure(message)
    }

    private suspend fun commit(
        repositoryId: Uuid,
        path: String,
        content: String,
        message: String,
        authorName: String,
        authorEmail: String
    ): SyncResult {
        val result = writeService.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                path = path,
                content = content,
                message = message,
                authorName = authorName,
                authorEmail = authorEmail
            )
        )
        return SyncResult.Ok(commitSha = result.commitSha)
    }

    override suspend fun pullFromGit(repositoryId: Uuid, commitSha: String): SyncResult {
        val files = readAllFiles(repositoryId, commitSha)

        // Single pass partitions parsed files by type and collects parse errors. Compared to
        // 4× `filterIsInstance().map { it.file }`, this allocates 4 result lists once
        // and walks the input exactly once.
        val agents = mutableListOf<AgentFile>()
        val tools = mutableListOf<ToolFile>()
        val mcpServers = mutableListOf<McpServerFile>()
        val prompts = mutableListOf<PromptFile>()
        val resources = mutableListOf<ResourceFile>()
        val parseErrors = mutableListOf<RepoValidationError>()
        for ((path, content) in files) {
            when (val p = parser.parse(path, content)) {
                is ParsedFile.Agent -> agents += p.file
                is ParsedFile.Tool -> tools += p.file
                is ParsedFile.McpServer -> mcpServers += p.file
                is ParsedFile.Prompt -> prompts += p.file
                is ParsedFile.Resource -> resources += p.file
                is ParsedFile.ParseError -> parseErrors += RepoValidationError(p.path, p.message)
                is ParsedFile.UnknownPath -> Unit // not under our known dirs; ignore
            }
        }
        if (parseErrors.isNotEmpty()) return SyncResult.ValidationFailed(parseErrors)

        // Each `getKeyIndex()` is `select key, id from <table>` — no JSONB, no description
        // text, no prompt body. Cheap to call on every webhook even on large tables.
        val modelKeyToId = modelService.getKeyIndex()
        val promptKeyToId = promptService.getKeyIndex().toMutableMap()
        val toolKeyToId = agentToolService.getKeyIndex().toMutableMap()
        val mcpKeyToId = mcpServerService.getKeyIndex().toMutableMap()
        val agentKeyToId = agentService.getKeyIndex().toMutableMap()
        val scriptKeyToId = scriptService.getKeyIndex()

        val snapshot = AgentRepoTreeSnapshot(
            mcpServers = mcpServers,
            tools = tools,
            prompts = prompts,
            agents = agents,
            resources = resources,
            knownModelKeys = modelKeyToId.keys,
            knownPromptKeys = promptKeyToId.keys,
            knownToolKeys = toolKeyToId.keys,
            knownMcpServerKeys = mcpKeyToId.keys,
            knownSubAgentKeys = agentKeyToId.keys,
            knownScriptKeys = scriptKeyToId.keys,
        )

        when (val v = validator.validate(snapshot)) {
            is ValidationResult.Ok -> Unit
            is ValidationResult.Errors -> return SyncResult.ValidationFailed(v.errors)
        }

        // Resolve every metadata slug referenced by a resource up front. The slug→id map cannot
        // be pre-listed cheaply (unlike the key indexes), so the validator skips slug existence;
        // resolving here — before the transaction — turns a dangling slug into a ValidationFailed
        // with all such errors reported at once, instead of aborting the transaction mid-write.
        val slugErrors = mutableListOf<RepoValidationError>()
        val slugToMetadataId = mutableMapOf<String, Uuid>()
        for (file in resources) {
            for (slug in listOfNotNull(file.metadataSlug, file.documentMetadataSlug, file.contentMetadataSlug)) {
                if (slug in slugToMetadataId) continue
                val metadataId = slugService.get(slug)?.metadataId
                if (metadataId == null) {
                    slugErrors += RepoValidationError(file.path, "resource '${file.key}' references unknown metadata slug '$slug'")
                } else {
                    slugToMetadataId[slug] = metadataId
                }
            }
        }
        if (slugErrors.isNotEmpty()) return SyncResult.ValidationFailed(slugErrors)

        transaction {
            for (file in mcpServers) {
                mcpKeyToId[file.key] = upsertMcpServer(file, repositoryId).id
            }
            for (file in tools) {
                toolKeyToId[file.key] = upsertTool(file, repositoryId, mcpKeyToId, scriptKeyToId, modelKeyToId, promptKeyToId, agentKeyToId).id
            }
            for (file in prompts) {
                promptKeyToId[file.key] = upsertPrompt(file, repositoryId).id
            }
            for (file in resources) {
                upsertResource(file, repositoryId, slugToMetadataId, scriptKeyToId)
            }
            for (file in agents) {
                agentKeyToId[file.key] = upsertAgent(file, repositoryId, modelKeyToId, promptKeyToId).id
            }
            // Pass 2: wire join tables now that all referenced ids exist.
            for (file in agents) {
                val agentId = agentKeyToId.getValue(file.key)
                agentService.setSubAgents(agentId, file.subAgentKeys.map { agentKeyToId.getValue(it) })
                agentService.setTools(agentId, file.toolKeys.map { toolKeyToId.getValue(it) })
            }
        }
        return SyncResult.Ok(commitSha = commitSha)
    }

    override suspend fun onPushEvent(repositoryId: Uuid, beforeSha: String, afterSha: String) {
        val changed = browseService.listChangedPaths(repositoryId, beforeSha, afterSha)
        if (changed.none { isAgentRepoPath(it) }) return
        when (val result = pullFromGit(repositoryId, afterSha)) {
            is SyncResult.Ok -> Unit
            is SyncResult.ValidationFailed -> log.warn(
                "AGENT_PROJECT pull at {} for repo {} failed validation: {}",
                afterSha, repositoryId, result.errors.joinToString("; ") { "${it.path}: ${it.message}" }
            )
            is SyncResult.Failure -> log.error(
                "AGENT_PROJECT pull at {} for repo {} failed: {}", afterSha, repositoryId, result.message
            )
        }
    }

    override suspend fun backfill(
        repositoryId: Uuid,
        entries: List<BackfillEntry>,
        authorName: String,
        authorEmail: String
    ): SyncResult {
        val errors = mutableListOf<String>()
        var lastCommitSha: String? = null
        for (entry in entries) {
            link(entry, repositoryId)
            when (val result = pushToGit(entry.entityType, entry.entityId, authorName, authorEmail)) {
                is SyncResult.Ok -> result.commitSha?.let { lastCommitSha = it }
                is SyncResult.ValidationFailed -> errors += "${entry.gitPath}: " +
                    result.errors.joinToString("; ") { "${it.path}: ${it.message}" }
                is SyncResult.Failure -> errors += "${entry.gitPath}: ${result.message}"
            }
        }
        return if (errors.isEmpty()) SyncResult.Ok(commitSha = lastCommitSha)
        else SyncResult.Failure(errors.joinToString("\n"))
    }

    private suspend fun link(entry: BackfillEntry, repositoryId: Uuid) {
        when (entry.entityType) {
            AgentEntityType.AGENT -> agentService.linkToGit(entry.entityId, repositoryId, entry.gitPath)
            AgentEntityType.AGENT_TOOL -> agentToolService.linkToGit(entry.entityId, repositoryId, entry.gitPath)
            AgentEntityType.MCP_SERVER -> mcpServerService.linkToGit(entry.entityId, repositoryId, entry.gitPath)
            AgentEntityType.PROMPT -> promptService.linkToGit(entry.entityId, repositoryId, entry.gitPath)
            AgentEntityType.AGENT_RESOURCE -> agentResourceService.linkToGit(entry.entityId, repositoryId, entry.gitPath)
        }
    }

    private suspend fun upsertMcpServer(file: McpServerFile, repositoryId: Uuid): McpServerRegistration {
        val existing = mcpServerService.getByGitRepository(repositoryId, file.path)
        val input = McpServerRegistrationInput(
            key = file.key,
            name = file.name,
            description = file.description,
            transportType = file.transportType,
            configuration = file.configuration,
            enabled = file.enabled,
            gitRepositoryId = repositoryId,
            gitPath = file.path,
        )
        return if (existing != null) {
            mcpServerService.edit(existing.id, input).also { mcpServerService.setSyncError(it.id, null) }
        } else {
            mcpServerService.add(input).also { mcpServerService.setSyncError(it.id, null) }
        }
    }

    private suspend fun upsertTool(
        file: ToolFile,
        repositoryId: Uuid,
        mcpKeyToId: Map<String, Uuid>,
        scriptKeyToId: Map<String, Uuid>,
        modelKeyToId: Map<String, Uuid>,
        promptKeyToId: Map<String, Uuid>,
        agentKeyToId: Map<String, Uuid>,
    ): AgentTool {
        val existing = agentToolService.getByGitRepository(repositoryId, file.path)
        val input = AgentToolInput(
            key = file.key,
            name = file.name,
            description = file.description,
            configuration = file.configuration,
            scriptId = file.scriptKey?.let { scriptKeyToId.getValue(it) },
            mcpServerId = file.mcpServerKey?.let { mcpKeyToId.getValue(it) },
            graphqlOperation = file.graphqlOperation,
            graphqlInputTransform = file.graphqlInputTransform,
            graphqlOutputTransform = file.graphqlOutputTransform,
            promptId = file.promptKey?.let { promptKeyToId.getValue(it) },
            modelId = file.modelKey?.let { modelKeyToId.getValue(it) },
            agentId = file.agentKey?.let { agentKeyToId.getValue(it) },
            gitRepositoryId = repositoryId,
            gitPath = file.path,
        )
        val tool = if (existing != null) agentToolService.edit(existing.id, input) else agentToolService.add(input)
        agentToolService.setSyncError(tool.id, null)
        return tool
    }

    private suspend fun upsertResource(
        file: ResourceFile,
        repositoryId: Uuid,
        slugToMetadataId: Map<String, Uuid>,
        scriptKeyToId: Map<String, Uuid>,
    ): AgentResource {
        val existing = agentResourceService.getByGitRepository(repositoryId, file.path)
        val input = AgentResourceInput(
            key = file.key,
            name = file.name,
            description = file.description,
            configuration = file.configuration,
            staticText = file.staticText,
            metadataId = file.metadataSlug?.let { slugToMetadataId.getValue(it) },
            documentMetadataId = file.documentMetadataSlug?.let { slugToMetadataId.getValue(it) },
            documentVersion = file.documentVersion,
            contentMetadataId = file.contentMetadataSlug?.let { slugToMetadataId.getValue(it) },
            scriptId = file.scriptKey?.let { scriptKeyToId.getValue(it) },
            graphqlOperation = file.graphqlOperation,
            graphqlInputTransform = file.graphqlInputTransform,
            graphqlOutputTransform = file.graphqlOutputTransform,
            gitRepositoryId = repositoryId,
            gitPath = file.path,
        )
        val resource = if (existing != null) agentResourceService.edit(existing.id, input) else agentResourceService.add(input)
        agentResourceService.setSyncError(resource.id, null)
        return resource
    }

    private suspend fun upsertPrompt(file: PromptFile, repositoryId: Uuid): Prompt {
        val existing = promptService.getByGitRepository(repositoryId, file.path)
        val input = PromptInput(
            key = file.key,
            name = file.name,
            description = file.description,
            inputType = file.inputType,
            outputType = file.outputType,
            schema = file.schema,
            systemPrompt = file.systemPrompt,
            userPrompt = file.userPrompt,
            gitRepositoryId = repositoryId,
            gitPath = file.path,
        )
        val prompt = if (existing != null) promptService.edit(existing.id, input) else promptService.add(input)
        promptService.setSyncError(prompt.id, null)
        return prompt
    }

    private suspend fun upsertAgent(
        file: AgentFile,
        repositoryId: Uuid,
        modelKeyToId: Map<String, Uuid>,
        promptKeyToId: Map<String, Uuid>
    ): Agent {
        val existing = agentService.getByGitRepository(repositoryId, file.path)
        val input = AgentInput(
            key = file.key,
            name = file.name,
            description = file.description,
            modelId = modelKeyToId.getValue(file.modelKey),
            promptId = promptKeyToId.getValue(file.promptKey),
            configuration = file.configuration,
            gitRepositoryId = repositoryId,
            gitPath = file.path,
        )
        val agent = if (existing != null) agentService.edit(existing.id, input) else agentService.add(input)
        agentService.setSyncError(agent.id, null)
        return agent
    }

    private suspend fun readAllFiles(repositoryId: Uuid, commitSha: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (dir in listOf(MCP_SERVERS_DIR, TOOLS_DIR, PROMPTS_DIR, RESOURCES_DIR, AGENTS_DIR)) {
            val entries = browseService.listTree(repositoryId, commitSha, dir)
            for (entry in entries) {
                if (entry.type != TreeEntryType.BLOB) continue
                if (!entry.path.endsWith(AgentRepoLayout.MD_EXTENSION)) continue
                val blob = browseService.readBlob(repositoryId, commitSha, entry.path) ?: continue
                val content = blob.content ?: continue
                out += entry.path to content
            }
        }
        return out
    }

    private fun isAgentRepoPath(path: String): Boolean =
        path.startsWith("$AGENTS_DIR/") ||
            path.startsWith("$TOOLS_DIR/") ||
            path.startsWith("$MCP_SERVERS_DIR/") ||
            path.startsWith("$PROMPTS_DIR/") ||
            path.startsWith("$RESOURCES_DIR/")
}
