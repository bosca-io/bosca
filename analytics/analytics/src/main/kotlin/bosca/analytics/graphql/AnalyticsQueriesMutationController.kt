package bosca.analytics.graphql

import bosca.analytics.jobs.AnalyticsQueryRefreshJob
import bosca.analytics.jobs.enqueue
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryGitSyncService
import bosca.analytics.service.AnalyticsQueryService
import bosca.di.ObjectProvider
import bosca.git.model.SourceRefInput
import bosca.git.service.SourceRefService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

object AnalyticsQueriesMutation

@TypeController
class AnalyticsQueriesMutationController(
    private val queriesService: AnalyticsQueryService,
    private val sourceRefService: ObjectProvider<SourceRefService>,
    private val gitSyncService: ObjectProvider<AnalyticsQueryGitSyncService>,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: AnalyticsQueryPermissionEvaluator
) : GraphQLController<AnalyticsQueriesMutation> {

    private val log = LoggerFactory.getLogger(AnalyticsQueriesMutationController::class.java)

    @Field
    suspend fun add(authentication: AuthenticationContext, query: AnalyticsQueryInput, sourceRef: SourceRefInput?): AnalyticsQuery {
        verifyCanManage(authentication)
        val created = queriesService.addQuery(query)
        if (sourceRef != null && sourceRefService.exists) {
            sourceRefService.get().setQuerySourceRef(created.id, sourceRef)
        }
        return created
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        query: AnalyticsQueryInput,
        sourceRef: SourceRefInput?,
        authorName: String? = null,
        authorEmail: String? = null,
    ): AnalyticsQuery {
        verifyCanManage(authentication)
        val updated = queriesService.editQuery(query)
        if (sourceRef != null && sourceRefService.exists) {
            sourceRefService.get().setQuerySourceRef(updated.id, sourceRef)
        }
        // Bidirectional sync: editQuery's transaction has committed by the time it
        // returns, so calling pushToGit here is naturally post-commit. Push is
        // best-effort — if it fails, we log and let the mutation succeed since
        // the DB write is already durable and the explicit pushToGit mutation
        // can be used to retry.
        if (gitSyncService.exists && authorName != null && authorEmail != null) {
            try {
                gitSyncService.get().pushToGit(updated.id, authorName, authorEmail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to push analytics query {} back to git after edit", updated.id, e)
            }
        }
        return updated
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        queriesService.deleteQueryById(id)
        return true
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val query = queriesService.getQueryById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.MANAGE)
        queriesService.addPermission(permission)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    @Field
    suspend fun deletePermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val query = queriesService.getQueryById(permission.entityId)
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.MANAGE)
        queriesService.deletePermission(permission)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    @Field
    suspend fun refresh(authentication: AuthenticationContext, queryId: UUID): Boolean {
        val query = queriesService.getQueryById(queryId)
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE)
        if (query.refreshIntervalSeconds == null) {
            return false
        }
        AnalyticsQueryRefreshJob(queryId = queryId).enqueue()
        return true
    }

    @Field
    suspend fun pushToGit(
        authentication: AuthenticationContext,
        queryId: UUID,
        authorName: String,
        authorEmail: String,
    ): String? {
        verifyCanManage(authentication)
        if (!gitSyncService.exists) return null
        return gitSyncService.get().pushToGit(queryId, authorName, authorEmail)
    }

    @Field
    suspend fun pushAllToGit(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        authorName: String,
        authorEmail: String,
    ): Int {
        verifyCanManage(authentication)
        if (!gitSyncService.exists) return 0
        return gitSyncService.get().pushAllToGit(repositoryId, authorName, authorEmail)
    }

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canManage = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canManage) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
