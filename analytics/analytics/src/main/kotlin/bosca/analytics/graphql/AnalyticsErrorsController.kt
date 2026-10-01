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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        // Fetch edges and total in parallel; they share the same filter
        // predicate and don't need a transaction around them (the list
        // view tolerates a tiny skew between count and page contents).
        return coroutineScope {
            val edgesAsync = async {
                errorGroupService.list(
                    appId = filter?.appId,
                    status = filter?.status,
                    fatal = filter?.fatal,
                    search = filter?.search,
                    offset = effectiveOffset,
                    limit = effectiveLimit,
                )
            }
            val totalAsync = async {
                errorGroupService.count(
                    appId = filter?.appId,
                    status = filter?.status,
                    fatal = filter?.fatal,
                    search = filter?.search,
                )
            }
            ErrorGroupConnection(edges = edgesAsync.await(), total = totalAsync.await())
        }
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
