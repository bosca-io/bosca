@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.graphql

import bosca.ai.agents.git.AgentEntityType
import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.SyncResult
import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.ai.agents.service.McpServerRegistrationService
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

object McpServersMutation

@TypeController
class McpServersMutationController(
    private val service: McpServerRegistrationService,
    private val gitSyncService: ObjectProvider<AgentGitSyncService>,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<McpServersMutation> {

    private val log = LoggerFactory.getLogger(McpServersMutationController::class.java)

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, server: McpServerRegistrationInput): McpServerRegistration {
        verifyCanManage(authentication)
        return service.add(server)
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        server: McpServerRegistrationInput,
        authorName: String? = null,
        authorEmail: String? = null,
    ): McpServerRegistration {
        verifyCanManage(authentication)
        val updated = service.edit(id, server)
        if (gitSyncService.exists && authorName != null && authorEmail != null) {
            try {
                val result = gitSyncService.get().pushToGit(AgentEntityType.MCP_SERVER, updated.id, authorName, authorEmail)
                if (result !is SyncResult.Ok) {
                    log.warn("Push to Git after edit failed for MCP server {}: {}", updated.id, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to push MCP server {} back to Git after edit", updated.id, e)
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
