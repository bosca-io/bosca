package bosca.ai.agents.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.agents.model.AgentTool
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AgentToolRepository {

    @Query("select * from agent_tools order by name")
    suspend fun getAll(): List<AgentTool>

    @Query("select * from agent_tools where id = :id")
    suspend fun getById(id: UUID): AgentTool?

    @Query("select * from agent_tools where key = :key")
    suspend fun getByKey(key: String): AgentTool?

    @Query("select * from agent_tools where script_id = :scriptId order by name")
    suspend fun getByScriptId(scriptId: UUID): List<AgentTool>

    @Query("select * from agent_tools where git_repository_id = :repositoryId and git_path = :gitPath")
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentTool?

    @Query("select key, id from agent_tools")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into agent_tools (key, name, description, configuration, script_id, mcp_server_id, graphql_operation, graphql_input_transform, graphql_output_transform, prompt_id, model_id, agent_id, git_repository_id, git_path) values (:key, :name, :description, :configuration, :scriptId, :mcpServerId, :graphqlOperation, :graphqlInputTransform, :graphqlOutputTransform, :promptId, :modelId, :agentId, :gitRepositoryId, :gitPath) returning *")
    suspend fun add(tool: AgentTool): AgentTool

    @Query("update agent_tools set key = :key, name = :name, description = :description, configuration = :configuration, script_id = :scriptId, mcp_server_id = :mcpServerId, graphql_operation = :graphqlOperation, graphql_input_transform = :graphqlInputTransform, graphql_output_transform = :graphqlOutputTransform, prompt_id = :promptId, model_id = :modelId, agent_id = :agentId where id = :id returning *")
    suspend fun update(tool: AgentTool): AgentTool

    @Query("update agent_tools set git_repository_id = :gitRepositoryId, git_path = :gitPath where id = :id")
    suspend fun linkToGit(id: UUID, gitRepositoryId: UUID?, gitPath: String?)

    @Query("update agent_tools set last_sync_error = :lastSyncError where id = :id")
    suspend fun setSyncError(id: UUID, lastSyncError: String?)

    @Query("delete from agent_tools where id = :id")
    suspend fun deleteById(id: UUID)
}
