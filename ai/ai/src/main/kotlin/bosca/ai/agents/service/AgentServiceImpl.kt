package bosca.ai.agents.service

import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentInput
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.repository.AgentRepository
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AgentServiceImpl(
    private val repository: AgentRepository,
) : AgentService {

    override suspend fun getAll(): List<Agent> = repository.getAll()

    override suspend fun get(id: UUID): Agent? = repository.getById(id)

    override suspend fun getByKey(key: String): Agent? = repository.getByKey(key)

    override suspend fun getByKeys(keys: List<String>): List<Agent> = repository.getByKeys(keys)

    override suspend fun add(input: AgentInput): Agent {
        val agent = Agent(
            key = input.key,
            name = input.name,
            description = input.description,
            modelId = input.modelId,
            promptId = input.promptId,
            configuration = input.configuration,
            gitRepositoryId = input.gitRepositoryId,
            gitPath = input.gitPath
        )
        return repository.add(agent)
    }

    override suspend fun edit(id: UUID, input: AgentInput): Agent {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Agent not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            modelId = input.modelId,
            promptId = input.promptId,
            configuration = input.configuration
        )
        return repository.update(updated)
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
    }

    override suspend fun getSubAgents(agentId: UUID): List<Agent> = repository.getSubAgents(agentId)

    override suspend fun addSubAgent(agentId: UUID, subAgentId: UUID, ordinal: Int) {
        repository.addSubAgent(agentId, subAgentId, ordinal)
    }

    override suspend fun removeSubAgent(agentId: UUID, subAgentId: UUID) {
        repository.removeSubAgent(agentId, subAgentId)
    }

    override suspend fun setSubAgents(agentId: UUID, subAgentIds: List<UUID>) = transaction {
        repository.removeAllSubAgents(agentId)
        subAgentIds.forEachIndexed { index, subAgentId ->
            repository.addSubAgent(agentId, subAgentId, index)
        }
    }

    override suspend fun getTools(agentId: UUID): List<AgentTool> = repository.getTools(agentId)

    override suspend fun addTool(agentId: UUID, toolId: UUID) {
        repository.addTool(agentId, toolId)
    }

    override suspend fun removeTool(agentId: UUID, toolId: UUID) {
        repository.removeTool(agentId, toolId)
    }

    override suspend fun setTools(agentId: UUID, toolIds: List<UUID>) = transaction {
        repository.removeAllTools(agentId)
        toolIds.forEach { toolId ->
            repository.addTool(agentId, toolId)
        }
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }

    override suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Agent? =
        repository.getByGitRepository(repositoryId, gitPath)

    override suspend fun linkToGit(id: UUID, repositoryId: UUID?, gitPath: String?) {
        repository.linkToGit(id, repositoryId, gitPath)
    }

    override suspend fun setSyncError(id: UUID, error: String?) {
        repository.setSyncError(id, error)
    }

}
