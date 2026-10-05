package bosca.git.ci.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineAgent
import bosca.serialization.UUID

@Repository
interface PipelineAgentRepository {

    @Query("select * from git.pipeline_agents where id = :id")
    suspend fun findById(id: UUID): PipelineAgent?

    @Query("select * from git.pipeline_agents where token_hash = :tokenHash")
    suspend fun findByTokenHash(tokenHash: String): PipelineAgent?

    @Query("select * from git.pipeline_agents where status = :status::git.agent_status order by name")
    suspend fun findByStatus(status: AgentStatus): List<PipelineAgent>

    @Query("select * from git.pipeline_agents order by name")
    suspend fun findAll(): List<PipelineAgent>

    @Query("""
        insert into git.pipeline_agents (
            name, labels, mode, status, ephemeral, job_id, parent_agent_id, token_hash, expires_at,
            api_token_credential_id, token_principal_id
        )
        values (
            :name, :labels, :mode::git.agent_mode, :status::git.agent_status, :ephemeral,
            :jobId, :parentAgentId, :tokenHash, :expiresAt, :apiTokenCredentialId, :tokenPrincipalId
        )
        returning *
    """)
    suspend fun create(agent: PipelineAgent): PipelineAgent

    @Query("update git.pipeline_agents set status = :status::git.agent_status where id = :id")
    suspend fun updateStatus(id: UUID, status: AgentStatus)

    @Query("update git.pipeline_agents set last_heartbeat = now() where id = :id")
    suspend fun heartbeat(id: UUID)

    @Query("update git.pipeline_agents set token_hash = :tokenHash where id = :id")
    suspend fun updateTokenHash(id: UUID, tokenHash: String)

    @Query("update git.pipeline_agents set name = :name where id = :id")
    suspend fun updateName(id: UUID, name: String)

    @Query("update git.pipeline_agents set labels = string_to_array(:labels, ',') where id = :id")
    suspend fun updateLabels(id: UUID, labels: String)

    @Query("update git.pipeline_agents set instance_id = :instanceId where id = :id")
    suspend fun setInstanceId(id: UUID, instanceId: String)

    @Query("update git.pipeline_agents set provider_config = :config::jsonb where id = :id")
    suspend fun updateProviderConfig(id: UUID, config: String)

    @Query("delete from git.pipeline_agents where id = :id")
    suspend fun delete(id: UUID)

    @Query("""
        select * from git.pipeline_agents
        where ephemeral = true
          and status != 'offline'
          and (expires_at < now() or (last_heartbeat < now() - interval '5 minutes' and last_heartbeat is not null))
    """)
    suspend fun findExpiredEphemeral(): List<PipelineAgent>

    @Query("""
        select * from git.pipeline_agents
        where ephemeral = true and status = 'offline' and created < now() - interval '1 hour'
    """)
    suspend fun findOrphanedEphemeral(): List<PipelineAgent>

    @Query("""
        select * from git.pipeline_agents
        where ephemeral = false
          and status in ('online', 'busy')
          and last_heartbeat is not null
          and last_heartbeat < now() - interval '5 minutes'
    """)
    suspend fun findStalePersistent(): List<PipelineAgent>
}
