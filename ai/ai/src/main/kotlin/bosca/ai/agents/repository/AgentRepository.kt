package bosca.ai.agents.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentTool
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AgentRepository {

    @Query("select * from agents order by name")
    suspend fun getAll(): List<Agent>

    @Query("select * from agents where id = :id")
    suspend fun getById(id: UUID): Agent?

    @Query("select * from agents where key = :key")
    suspend fun getByKey(key: String): Agent?

    @Query("select * from agents where key = any(:keys)")
    suspend fun getByKeys(keys: List<String>): List<Agent>

    @Query("select * from agents where git_repository_id = :repositoryId and git_path = :gitPath")
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): Agent?

    @Query("select key, id from agents")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into agents (key, name, description, model_id, prompt_id, configuration, git_repository_id, git_path) values (:key, :name, :description, :modelId, :promptId, :configuration, :gitRepositoryId, :gitPath) returning *")
    suspend fun add(agent: Agent): Agent

    @Query("update agents set key = :key, name = :name, description = :description, model_id = :modelId, prompt_id = :promptId, configuration = :configuration where id = :id returning *")
    suspend fun update(agent: Agent): Agent

    @Query("update agents set git_repository_id = :gitRepositoryId, git_path = :gitPath where id = :id")
    suspend fun linkToGit(id: UUID, gitRepositoryId: UUID?, gitPath: String?)

    @Query("update agents set last_sync_error = :lastSyncError where id = :id")
    suspend fun setSyncError(id: UUID, lastSyncError: String?)

    @Query("delete from agents where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("select a.* from agents a join agent_sub_agents asa on a.id = asa.sub_agent_id where asa.agent_id = :agentId order by asa.ordinal")
    suspend fun getSubAgents(agentId: UUID): List<Agent>

    @Query("insert into agent_sub_agents (agent_id, sub_agent_id, ordinal) values (:agentId, :subAgentId, :ordinal)")
    suspend fun addSubAgent(agentId: UUID, subAgentId: UUID, ordinal: Int)

    @Query("delete from agent_sub_agents where agent_id = :agentId and sub_agent_id = :subAgentId")
    suspend fun removeSubAgent(agentId: UUID, subAgentId: UUID)

    @Query("delete from agent_sub_agents where agent_id = :agentId")
    suspend fun removeAllSubAgents(agentId: UUID)

    @Query("select t.* from agent_tools t join agent_agent_tools aat on t.id = aat.tool_id where aat.agent_id = :agentId order by t.name")
    suspend fun getTools(agentId: UUID): List<AgentTool>

    @Query("insert into agent_agent_tools (agent_id, tool_id) values (:agentId, :toolId)")
    suspend fun addTool(agentId: UUID, toolId: UUID)

    @Query("delete from agent_agent_tools where agent_id = :agentId and tool_id = :toolId")
    suspend fun removeTool(agentId: UUID, toolId: UUID)

    @Query("delete from agent_agent_tools where agent_id = :agentId")
    suspend fun removeAllTools(agentId: UUID)
}
