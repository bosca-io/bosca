package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupConnection
import bosca.analytics.model.ErrorGroupFilter
import bosca.analytics.service.ErrorGroupService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Marker type for the `Analytics.errors` GraphQL field — the entry point
 * to the error tracking subsystem (group list, group detail, AI summary
 * read).
 */
object AnalyticsErrors

/**
 * Read-side GraphQL controller for error tracking.
 *
 * All operations require either the `analytics.manager` group or
 * platform admins. Filtering and paging are delegated to
 * [ErrorGroupService], which fans out to the per-fingerprint Postgres
 * aggregates.
 */
@TypeController
class AnalyticsErrorsController(
    private val errorGroupService: ErrorGroupService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AnalyticsErrors> {

    @Field
    suspend fun groups(
        authentication: AuthenticationContext,
        filter: ErrorGroupFilter? = null,
        offset: Long? = null,
        limit: Int? = null,
    ): ErrorGroupConnection {
        AnalyticsErrorsAuthorization.verifyCanRead(groupEvaluator, authentication)
        val effectiveOffset = (offset ?: 0L).coerceAtLeast(0L)
        val effectiveLimit = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val edges = errorGroupService.list(
            appId = filter?.appId,
            status = filter?.status,
            fatal = filter?.fatal,
            search = filter?.search,
            offset = effectiveOffset,
            limit = effectiveLimit,
        )
        val total = errorGroupService.count(
            appId = filter?.appId,
            status = filter?.status,
            fatal = filter?.fatal,
            search = filter?.search,
        )
        return ErrorGroupConnection(edges = edges, total = total)
    }

    @Field
    suspend fun group(
        authentication: AuthenticationContext,
        fingerprint: String,
    ): ErrorGroup? {
        AnalyticsErrorsAuthorization.verifyCanRead(groupEvaluator, authentication)
        return errorGroupService.getByFingerprint(fingerprint)
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 500
    }
}
