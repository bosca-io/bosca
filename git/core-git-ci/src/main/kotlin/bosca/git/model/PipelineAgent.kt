package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A registered build agent. Agents come in two flavors:
 *
 * - **Persistent**: Long-running CLI instances that subscribe to jobs
 *   and execute them locally (runner mode) or provision VMs (orchestrator mode).
 *
 * - **Transient** ([ephemeral] = true): Created by an orchestrator for a
 *   single job. Scoped to [jobId], linked to the orchestrator via
 *   [parentAgentId]. Automatically cleaned up after job completion or
 *   [expiresAt] deadline.
 */
@Serializable
data class PipelineAgent(
    @Contextual val id: UUID = UUID.NIL,
    val name: String,
    val labels: List<String> = listOf("default"),
    val mode: AgentMode = AgentMode.RUNNER,
    val status: AgentStatus = AgentStatus.OFFLINE,
    val ephemeral: Boolean = false,
    @Contextual @ColumnName("job_id") val jobId: UUID? = null,
    @Contextual @ColumnName("parent_agent_id") val parentAgentId: UUID? = null,
    @ColumnName("token_hash") val tokenHash: String,
    @ColumnName("provider_config") val providerConfig: String? = null,
    @ColumnName("instance_id") val instanceId: String? = null,
    @Contextual @ColumnName("last_heartbeat") val lastHeartbeat: OffsetDateTime? = null,
    @Contextual @ColumnName("expires_at") val expiresAt: OffsetDateTime? = null,
    /** API-token credential owned by this agent and revoked when the agent is removed. */
    @ColumnName("api_token_credential_id")
    val apiTokenCredentialId: Long? = null,
    /** Principal that owns [apiTokenCredentialId], required for server-side revocation. */
    @Contextual
    @ColumnName("token_principal_id")
    val tokenPrincipalId: UUID? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)
