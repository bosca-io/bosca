@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.prompt.graphql

import bosca.ai.agents.git.AgentEntityType
import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.SyncResult
import bosca.ai.prompts.model.Prompt
import bosca.ai.prompts.model.PromptInput
import bosca.ai.prompts.service.PromptService
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

object PromptsMutation

@TypeController
class PromptsMutationController(
    private val service: PromptService,
    private val gitSyncService: ObjectProvider<AgentGitSyncService>,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<PromptsMutation> {

    private val log = LoggerFactory.getLogger(PromptsMutationController::class.java)

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "prompt.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, prompt: PromptInput): Prompt {
        verifyCanManage(authentication)
        return service.add(prompt)
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        prompt: PromptInput,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Prompt {
        verifyCanManage(authentication)
        val updated = service.edit(id, prompt)
        if (gitSyncService.exists && authorName != null && authorEmail != null) {
            try {
                val result = gitSyncService.get().pushToGit(AgentEntityType.PROMPT, updated.id, authorName, authorEmail)
                if (result !is SyncResult.Ok) {
                    log.warn("Push to Git after edit failed for prompt {}: {}", updated.id, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to push prompt {} back to Git after edit", updated.id, e)
            }
        }
        return updated
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        service.delete(id)
        return true
    }

}
