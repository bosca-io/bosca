@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.git.model.RepositoryContentType
import bosca.git.service.RepositoryContentValidationError
import bosca.git.service.RepositoryContentValidator
import bosca.scripting.service.ScriptService
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * AGENT_PROJECT pre-receive validator. The hook hands us a snapshot of every file in
 * the proposed tree that matches one of our directory prefixes; we parse it,
 * gather the DB-side "known key" sets, and delegate to the pure [AgentRepoValidator].
 *
 * Symmetric to the validation path inside [AgentGitSyncServiceImpl.pullFromGit] — both
 * paths feed the same pure validator with the same snapshot shape, so a push that the
 * pre-receive hook accepts cannot later be rejected at pull time.
 *
 * Not registered by any composition root today: git pushes land on the dedicated
 * bosca-git-server, which loads no domain modules, so its pre-receive registry stays
 * empty and pull-time validation is the enforcement. This class is the AGENT_PROJECT
 * entry point to register if the git server ever gains a way to consult domain validators.
 */
class AgentProjectContentValidator(
    private val agentService: AgentService,
    private val agentToolService: AgentToolService,
    private val mcpServerService: McpServerRegistrationService,
    private val promptService: PromptService,
    private val modelService: ModelService,
    private val scriptService: ScriptService,
) : RepositoryContentValidator {

    private val parser = AgentRepoFileParser()
    private val validator: AgentRepoValidator = AgentRepoValidatorImpl()

    override val contentType: RepositoryContentType = RepositoryContentType.AGENT_PROJECT

    override val pathPrefixes: List<String> = listOf(
        "${AgentRepoLayout.AGENTS_DIR}/",
        "${AgentRepoLayout.TOOLS_DIR}/",
        "${AgentRepoLayout.MCP_SERVERS_DIR}/",
        "${AgentRepoLayout.PROMPTS_DIR}/",
        "${AgentRepoLayout.RESOURCES_DIR}/",
    )

    override suspend fun validate(
        repositoryId: Uuid,
        files: Map<String, String>,
    ): List<RepositoryContentValidationError> {
        val agents = mutableListOf<AgentFile>()
        val tools = mutableListOf<ToolFile>()
        val mcpServers = mutableListOf<McpServerFile>()
        val prompts = mutableListOf<PromptFile>()
        val resources = mutableListOf<ResourceFile>()
        val parseErrors = mutableListOf<RepositoryContentValidationError>()
        for ((path, content) in files) {
            when (val p = parser.parse(path, content)) {
                is ParsedFile.Agent -> agents += p.file
                is ParsedFile.Tool -> tools += p.file
                is ParsedFile.McpServer -> mcpServers += p.file
                is ParsedFile.Prompt -> prompts += p.file
                is ParsedFile.Resource -> resources += p.file
                is ParsedFile.ParseError -> parseErrors += RepositoryContentValidationError(p.path, p.message)
                is ParsedFile.UnknownPath -> Unit
            }
        }
        if (parseErrors.isNotEmpty()) return parseErrors

        val snapshot = AgentRepoTreeSnapshot(
            mcpServers = mcpServers,
            tools = tools,
            prompts = prompts,
            agents = agents,
            resources = resources,
            knownModelKeys = modelService.getKeyIndex().keys,
            knownPromptKeys = promptService.getKeyIndex().keys,
            knownToolKeys = agentToolService.getKeyIndex().keys,
            knownMcpServerKeys = mcpServerService.getKeyIndex().keys,
            knownSubAgentKeys = agentService.getKeyIndex().keys,
            knownScriptKeys = scriptService.getKeyIndex().keys,
        )

        return when (val result = validator.validate(snapshot)) {
            is ValidationResult.Ok -> emptyList()
            is ValidationResult.Errors -> result.errors.map {
                RepositoryContentValidationError(it.path, it.message)
            }
        }
    }
}
