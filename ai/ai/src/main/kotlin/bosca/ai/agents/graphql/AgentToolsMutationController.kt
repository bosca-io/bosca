@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.graphql

import bosca.ai.agents.git.AgentEntityType
import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.SyncResult
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.model.AgentToolInput
import bosca.ai.agents.service.AgentToolService
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

object AgentToolsMutation

@TypeController
class AgentToolsMutationController(
    private val service: AgentToolService,
    private val gitSyncService: ObjectProvider<AgentGitSyncService>,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentToolsMutation> {

    private val log = LoggerFactory.getLogger(AgentToolsMutationController::class.java)

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, tool: AgentToolInput): AgentTool {
        verifyCanManage(authentication)
        return service.add(tool)
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        tool: AgentToolInput,
        authorName: String? = null,
        authorEmail: String? = null,
    ): AgentTool {
        verifyCanManage(authentication)
        val updated = service.edit(id, tool)
        if (gitSyncService.exists && authorName != null && authorEmail != null) {
            try {
                val result = gitSyncService.get().pushToGit(AgentEntityType.AGENT_TOOL, updated.id, authorName, authorEmail)
                if (result !is SyncResult.Ok) {
                    log.warn("Push to Git after edit failed for tool {}: {}", updated.id, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to push tool {} back to Git after edit", updated.id, e)
            }
        }
        return updated
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        return service.delete(id)
    }

}
