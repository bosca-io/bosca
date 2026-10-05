package bosca.ai.agents.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.agents.model.McpServerRegistration
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface McpServerRegistrationRepository {

    @Query("select * from ai.mcp_server_registrations order by name")
    suspend fun getAll(): List<McpServerRegistration>

    @Query("select * from ai.mcp_server_registrations where enabled = true order by name")
    suspend fun getAllEnabled(): List<McpServerRegistration>

    @Query("select * from ai.mcp_server_registrations where id = :id")
    suspend fun getById(id: UUID): McpServerRegistration?

    @Query("select * from ai.mcp_server_registrations where key = :key")
    suspend fun getByKey(key: String): McpServerRegistration?

    @Query("select * from ai.mcp_server_registrations where git_repository_id = :repositoryId and git_path = :gitPath")
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): McpServerRegistration?

    @Query("select key, id from ai.mcp_server_registrations")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into ai.mcp_server_registrations (key, name, description, transport_type, configuration, enabled, git_repository_id, git_path) values (:key, :name, :description, :transportType, :configuration, :enabled, :gitRepositoryId, :gitPath) returning *")
    suspend fun add(registration: McpServerRegistration): McpServerRegistration

    @Query("update ai.mcp_server_registrations set key = :key, name = :name, description = :description, transport_type = :transportType, configuration = :configuration, enabled = :enabled where id = :id returning *")
    suspend fun update(registration: McpServerRegistration): McpServerRegistration

    @Query("update ai.mcp_server_registrations set git_repository_id = :gitRepositoryId, git_path = :gitPath where id = :id")
    suspend fun linkToGit(id: UUID, gitRepositoryId: UUID?, gitPath: String?)

    @Query("update ai.mcp_server_registrations set last_sync_error = :lastSyncError where id = :id")
    suspend fun setSyncError(id: UUID, lastSyncError: String?)

    @Query("delete from ai.mcp_server_registrations where id = :id")
    suspend fun deleteById(id: UUID)
}
