package bosca.ai.agents.service

import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.model.AgentToolInput
import bosca.ai.agents.repository.AgentToolRepository
import bosca.ai.agents.validation.McpImplValidation
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AgentToolServiceImpl(
    private val repository: AgentToolRepository,
) : AgentToolService {

    override suspend fun getAll(): List<AgentTool> = repository.getAll()

    override suspend fun get(id: UUID): AgentTool? = repository.getById(id)

    override suspend fun getByKey(key: String): AgentTool? = repository.getByKey(key)

    override suspend fun getByScriptId(scriptId: UUID): List<AgentTool> = repository.getByScriptId(scriptId)

    override suspend fun add(input: AgentToolInput): AgentTool {
        McpImplValidation.validate(input)
        val tool = AgentTool(
            key = input.key,
            name = input.name,
            description = input.description,
            configuration = input.configuration,
            scriptId = input.scriptId,
            mcpServerId = input.mcpServerId,
            graphqlOperation = input.graphqlOperation,
            graphqlInputTransform = input.graphqlInputTransform,
            graphqlOutputTransform = input.graphqlOutputTransform,
            promptId = input.promptId,
            modelId = input.modelId,
            agentId = input.agentId,
            gitRepositoryId = input.gitRepositoryId,
            gitPath = input.gitPath
        )
        return repository.add(tool)
    }

    override suspend fun edit(id: UUID, input: AgentToolInput): AgentTool = transaction {
        McpImplValidation.validate(input)
        val existing = repository.getById(id) ?: throw NoSuchElementException("AgentTool not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            configuration = input.configuration,
            scriptId = input.scriptId,
            mcpServerId = input.mcpServerId,
            graphqlOperation = input.graphqlOperation,
            graphqlInputTransform = input.graphqlInputTransform,
            graphqlOutputTransform = input.graphqlOutputTransform,
            promptId = input.promptId,
            modelId = input.modelId,
            agentId = input.agentId
        )
        repository.update(updated)
    }

    override suspend fun delete(id: UUID): Boolean {
        val existing = repository.getById(id) ?: return false
        repository.deleteById(existing.id)
        return true
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }

    override suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentTool? =
        repository.getByGitRepository(repositoryId, gitPath)

    override suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?) {
        repository.linkToGit(id, repositoryId, gitPath)
    }

    override suspend fun setSyncError(id: UUID, error: String?) {
        repository.setSyncError(id, error)
    }

}
