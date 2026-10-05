package bosca.git.service

import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineAgent
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages build agent registration, heartbeats, and lifecycle.
 * Supports both persistent agents (long-running CLI instances) and
 * transient agents (ephemeral VMs created by orchestrators for a
 * single job).
 */
interface PipelineAgentService : Service {

    /**
     * Registers a new persistent build agent. Returns the agent record
     * and the raw token (only available at registration time).
     */
    suspend fun register(name: String, labels: List<String>, mode: AgentMode, principalId: UUID): Pair<PipelineAgent, String>

    /**
     * Registers a transient agent scoped to a single job. Called by
     * orchestrators before provisioning a VM. Sets [PipelineAgent.expiresAt]
     * based on the timeout.
     */
    suspend fun registerEphemeral(
        jobId: UUID,
        name: String,
        labels: List<String>,
        parentAgentId: UUID,
        timeoutMinutes: Int,
        principalId: UUID
    ): Pair<PipelineAgent, String>

    /**
     * Creates the uniquely identified ephemeral agent row used by a Kubernetes CI Job.
     *
     * Returns a unique, expiring API token for this job. The token is revoked when the agent is
     * removed, so Kubernetes workers never share a profile-wide CI credential. [lifetimeMinutes]
     * must cover both queue wait and execution time.
     */
    suspend fun registerKubernetesEphemeral(
        jobId: UUID,
        name: String,
        labels: List<String>,
        lifetimeMinutes: Long,
        principalId: UUID,
    ): Pair<PipelineAgent, String>

    /**
     * Retrieves an agent by its unique identifier.
     */
    suspend fun findById(id: UUID): PipelineAgent?

    /**
     * Authenticates an agent by its raw token. Returns the agent if
     * the token hash matches.
     */
    suspend fun findByToken(rawToken: String): PipelineAgent?

    /**
     * Records a heartbeat from an agent, updating [PipelineAgent.lastHeartbeat].
     */
    suspend fun heartbeat(agentId: UUID)

    /**
     * Updates the status of an agent.
     */
    suspend fun updateStatus(agentId: UUID, status: AgentStatus)

    /**
     * Updates the name and labels of a registered agent.
     */
    suspend fun updateAgent(agentId: UUID, name: String, labels: List<String>): PipelineAgent

    /**
     * Stores the hypervisor instance ID on a transient agent after
     * the VM has been provisioned.
     */
    suspend fun setInstanceId(agentId: UUID, instanceId: String)

    /**
     * Lists agents with an optional status filter.
     */
    suspend fun listAgents(status: AgentStatus? = null): List<PipelineAgent>

    /**
     * Deregisters an agent, removing its record. For transient agents,
     * this is called by the agent itself on job completion or by the
     * cleanup job on expiry.
     */
    suspend fun deregister(agentId: UUID)

    /**
     * Updates the orchestrator provider configuration (credentials,
     * VM profiles, resource limits). Stored as JSONB.
     */
    suspend fun updateProviderConfig(agentId: UUID, config: String): PipelineAgent

    /**
     * Finds transient agents that have expired or missed heartbeats.
     * Used by the cleanup job to detect leaked VMs.
     */
    suspend fun findExpiredEphemeralAgents(): List<PipelineAgent>

    /**
     * Finds persistent agents whose last heartbeat is older than the
     * staleness threshold (5 minutes). Used by the cleanup job to
     * transition zombie agents back to OFFLINE.
     */
    suspend fun findStalePersistentAgents(): List<PipelineAgent>
}
