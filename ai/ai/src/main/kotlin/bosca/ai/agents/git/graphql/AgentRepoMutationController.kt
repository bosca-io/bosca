@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git.graphql

import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.git.AgentRepoBackfillEntry
import bosca.ai.agents.git.AgentRepoBackfillResult
import bosca.ai.agents.git.AgentRepoValidationError
import bosca.ai.agents.git.BackfillEntry
import bosca.ai.agents.git.SyncResult
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/** GraphQL marker type for AGENT_PROJECT repository mutations. */
object AgentRepoMutation

/**
 * Bulk-export ("backfill") mutation surface for AGENT_PROJECT repositories. Accepts a
 * list of `(entityType, entityId, gitPath)` triples, links each row to the target path,
 * and pushes them to the repo via [AgentGitSyncService.backfill].
 *
 * Permissioning matches `AgentsMutationController` — admin or `agent.manager` group.
 */
@TypeController
class AgentRepoMutationController(
    private val service: AgentGitSyncService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentRepoMutation> {

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "agent.manager") ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun backfill(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        entries: List<AgentRepoBackfillEntry>,
        authorName: String,
        authorEmail: String,
    ): AgentRepoBackfillResult {
        verifyCanManage(authentication)
        val internalEntries = entries.map { BackfillEntry(it.entityType, it.entityId, it.gitPath) }
        return when (val result = service.backfill(repositoryId, internalEntries, authorName, authorEmail)) {
            is SyncResult.Ok -> AgentRepoBackfillResult(ok = true, commitSha = result.commitSha)
            is SyncResult.ValidationFailed -> AgentRepoBackfillResult(
                ok = false,
                validationErrors = result.errors.map { AgentRepoValidationError(it.path, it.message) },
            )
            is SyncResult.Failure -> AgentRepoBackfillResult(ok = false, errorMessage = result.message)
        }
    }
}
