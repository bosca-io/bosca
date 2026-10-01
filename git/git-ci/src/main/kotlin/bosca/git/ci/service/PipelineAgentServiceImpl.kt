package bosca.git.ci.service

import bosca.git.ci.repository.PipelineAgentRepository
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineAgent
import bosca.git.service.PipelineAgentService
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.security.MessageDigest

@ServiceImplementation
class PipelineAgentServiceImpl(
    private val agentRepository: PipelineAgentRepository,
    private val apiTokenService: ApiTokenService
) : PipelineAgentService {

    override suspend fun register(name: String, labels: List<String>, mode: AgentMode, principalId: UUID): Pair<PipelineAgent, String> {
        val tokenResult = apiTokenService.createToken(
            principalId = principalId,
            input = ApiTokenInput(name = "ci-agent-$name", scopes = AGENT_SCOPES),
            createdBy = principalId
        )

        val agent = agentRepository.create(
            PipelineAgent(
                name = name,
                labels = labels,
                mode = mode,
                status = AgentStatus.OFFLINE,
                ephemeral = false,
                tokenHash = hashToken(tokenResult.rawToken),
                apiTokenCredentialId = tokenResult.credential.id,
                tokenPrincipalId = principalId,
            )
        )

        return agent to tokenResult.rawToken
    }

    override suspend fun registerEphemeral(
        jobId: UUID,
        name: String,
        labels: List<String>,
        parentAgentId: UUID,
        timeoutMinutes: Int,
        principalId: UUID
    ): Pair<PipelineAgent, String> {
        val parent = agentRepository.findById(parentAgentId)
        val maxTimeout = parent?.providerConfig?.let { parseMaxTimeout(it) } ?: 90
        val effectiveTimeout = timeoutMinutes.coerceAtMost(maxTimeout)

        val tokenResult = apiTokenService.createToken(
            principalId = principalId,
            input = ApiTokenInput(name = "ci-ephemeral-$name", scopes = AGENT_SCOPES),
            createdBy = principalId
        )

        val agent = agentRepository.create(
            PipelineAgent(
                name = name,
                labels = labels,
                mode = AgentMode.RUNNER,
                status = AgentStatus.OFFLINE,
                ephemeral = true,
                jobId = jobId,
                parentAgentId = parentAgentId,
                tokenHash = hashToken(tokenResult.rawToken),
                expiresAt = OffsetDateTime.now().plusMinutes(effectiveTimeout.toLong()),
                apiTokenCredentialId = tokenResult.credential.id,
                tokenPrincipalId = principalId,
            )
        )

        return agent to tokenResult.rawToken
    }

    override suspend fun registerKubernetesEphemeral(
        jobId: UUID,
        name: String,
        labels: List<String>,
        lifetimeMinutes: Long,
        principalId: UUID,
    ): Pair<PipelineAgent, String> {
        require(lifetimeMinutes > 0) { "Kubernetes CI agent lifetime must be positive" }
        val expiresAt = OffsetDateTime.now().plusMinutes(lifetimeMinutes)
        val tokenResult = apiTokenService.createEphemeralToken(
            principalId = principalId,
            input = ApiTokenInput(
                name = "ci-kubernetes-${jobId.toString().take(12)}",
                scopes = AGENT_SCOPES,
                expiresAt = expiresAt.toString(),
            ),
            createdBy = principalId,
        )
        val agent = agentRepository.create(
            PipelineAgent(
                name = name,
                labels = labels,
                mode = AgentMode.RUNNER,
                status = AgentStatus.OFFLINE,
                ephemeral = true,
                jobId = jobId,
                parentAgentId = null,
                tokenHash = hashToken(tokenResult.rawToken),
                expiresAt = expiresAt,
                apiTokenCredentialId = tokenResult.credential.id,
                tokenPrincipalId = principalId,
            )
        )
        return agent to tokenResult.rawToken
    }

    override suspend fun findById(id: UUID): PipelineAgent? {
        return agentRepository.findById(id)
    }

    override suspend fun findByToken(rawToken: String): PipelineAgent? {
        return agentRepository.findByTokenHash(hashToken(rawToken))
    }

    override suspend fun heartbeat(agentId: UUID) {
        agentRepository.heartbeat(agentId)
    }

    override suspend fun updateStatus(agentId: UUID, status: AgentStatus) {
        agentRepository.updateStatus(agentId, status)
    }

    override suspend fun updateAgent(agentId: UUID, name: String, labels: List<String>): PipelineAgent {
        agentRepository.updateName(agentId, name)
        agentRepository.updateLabels(agentId, labels.joinToString(","))
        return agentRepository.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
    }

    override suspend fun setInstanceId(agentId: UUID, instanceId: String) {
        agentRepository.setInstanceId(agentId, instanceId)
    }

    override suspend fun listAgents(status: AgentStatus?): List<PipelineAgent> {
        return if (status != null) agentRepository.findByStatus(status) else agentRepository.findAll()
    }

    override suspend fun deregister(agentId: UUID) {
        val agent = agentRepository.findById(agentId) ?: return
        val credentialId = agent.apiTokenCredentialId
        val principalId = agent.tokenPrincipalId
        if (credentialId != null && principalId != null) {
            apiTokenService.revokeToken(credentialId, principalId)
            apiTokenService.deleteToken(credentialId, principalId)
        }
        agentRepository.delete(agentId)
    }

    override suspend fun updateProviderConfig(agentId: UUID, config: String): PipelineAgent {
        agentRepository.updateProviderConfig(agentId, config)
        return agentRepository.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
    }

    override suspend fun findExpiredEphemeralAgents(): List<PipelineAgent> {
        return agentRepository.findExpiredEphemeral()
    }

    override suspend fun findStalePersistentAgents(): List<PipelineAgent> {
        return agentRepository.findStalePersistent()
    }

    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(rawToken.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun parseMaxTimeout(providerConfig: String): Int? {
        return try {
            val regex = """"maxJobTimeoutMinutes"\s*:\s*(\d+)""".toRegex()
            regex.find(providerConfig)?.groupValues?.get(1)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val AGENT_SCOPES = listOf(
            "ci:read",
            "ci:edit",
            "ci:execute",
            "git:read",
            "git:write",
            "storage:read",
            "storage:write",
            "artifacts:push",
        )
    }
}
