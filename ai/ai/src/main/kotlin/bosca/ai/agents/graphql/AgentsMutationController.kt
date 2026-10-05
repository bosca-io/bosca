@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.graphql

import bosca.ai.agents.git.AgentEntityType
import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.SyncResult
import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentInput
import bosca.ai.agents.service.AgentService
import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object AgentsMutation

@TypeController
class AgentsMutationController(
    private val service: AgentService,
    private val gitSyncService: ObjectProvider<AgentGitSyncService>,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentsMutation> {

    private val log = LoggerFactory.getLogger(AgentsMutationController::class.java)

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    private suspend fun pushAgentAfterEdit(agentId: Uuid, authorName: String?, authorEmail: String?) {
        if (!gitSyncService.exists || authorName == null || authorEmail == null) return
        try {
            val result = gitSyncService.get().pushToGit(AgentEntityType.AGENT, agentId, authorName, authorEmail)
            if (result !is SyncResult.Ok) {
                log.warn("Push to Git after edit failed for agent {}: {}", agentId, result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to push agent {} back to Git after edit", agentId, e)
        }
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, agent: AgentInput): Agent {
        verifyCanManage(authentication)
        return service.add(agent)
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        agent: AgentInput,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Agent {
        verifyCanManage(authentication)
        val updated = service.edit(id, agent)
        pushAgentAfterEdit(updated.id, authorName, authorEmail)
        return updated
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        service.delete(id)
        return true
    }

    @Field
    suspend fun addSubAgent(
        authentication: AuthenticationContext,
        agentId: UUID,
        subAgentId: UUID,
        ordinal: Int,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.addSubAgent(agentId, subAgentId, ordinal)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

    @Field
    suspend fun removeSubAgent(
        authentication: AuthenticationContext,
        agentId: UUID,
        subAgentId: UUID,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.removeSubAgent(agentId, subAgentId)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

    @Field
    suspend fun setSubAgents(
        authentication: AuthenticationContext,
        agentId: UUID,
        subAgentIds: List<UUID>,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.setSubAgents(agentId, subAgentIds)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

    @Field
    suspend fun addTool(
        authentication: AuthenticationContext,
        agentId: UUID,
        toolId: UUID,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.addTool(agentId, toolId)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

    @Field
    suspend fun removeTool(
        authentication: AuthenticationContext,
        agentId: UUID,
        toolId: UUID,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.removeTool(agentId, toolId)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

    @Field
    suspend fun setTools(
        authentication: AuthenticationContext,
        agentId: UUID,
        toolIds: List<UUID>,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Boolean {
        verifyCanManage(authentication)
        service.setTools(agentId, toolIds)
        pushAgentAfterEdit(agentId, authorName, authorEmail)
        return true
    }

}
