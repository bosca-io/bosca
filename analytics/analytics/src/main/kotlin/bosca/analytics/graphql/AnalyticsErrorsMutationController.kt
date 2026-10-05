package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.service.ErrorGroupAnalysisService
import bosca.analytics.service.ErrorGroupService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

/**
 * Marker type for the `AnalyticsMutation.errors` GraphQL field — write
 * operations against the error tracking subsystem.
 */
object AnalyticsErrorsMutation

/**
 * Write-side GraphQL controller for error tracking. All operations
 * require the `analytics.manager` group (or admin) — read-only viewers
 * cannot mutate group state.
 *
 * The `analyze` mutation routes through [ErrorGroupAnalysisService]
 * which is supplied by the `analytics-ai` module when present, falling
 * back to a no-op implementation that returns the current group
 * unchanged. This keeps the GraphQL surface consistent regardless of
 * whether AI features are wired up.
 */
@TypeController
class AnalyticsErrorsMutationController(
    private val errorGroupService: ErrorGroupService,
    private val analysisService: ErrorGroupAnalysisService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AnalyticsErrorsMutation> {

    @Field
    suspend fun setStatus(
        authentication: AuthenticationContext,
        fingerprint: String,
        status: ErrorGroupStatus,
    ): ErrorGroup {
        AnalyticsErrorsAuthorization.verifyCanManage(groupEvaluator, authentication)
        log.info(
            "setStatus: fingerprint={} status={} by={}",
            fingerprint, status, authentication.principal()?.id,
        )
        return errorGroupService.setStatus(fingerprint, status)
    }

    @Field
    suspend fun assign(
        authentication: AuthenticationContext,
        fingerprint: String,
        assigneeId: UUID? = null,
    ): ErrorGroup {
        AnalyticsErrorsAuthorization.verifyCanManage(groupEvaluator, authentication)
        log.info(
            "assign: fingerprint={} assigneeId={} by={}",
            fingerprint, assigneeId, authentication.principal()?.id,
        )
        return errorGroupService.assign(fingerprint, assigneeId)
    }

    @Field
    suspend fun analyze(
        authentication: AuthenticationContext,
        fingerprint: String,
    ): ErrorGroup {
        AnalyticsErrorsAuthorization.verifyCanManage(groupEvaluator, authentication)
        log.info(
            "analyze: fingerprint={} by={}",
            fingerprint, authentication.principal()?.id,
        )
        return analysisService.analyze(fingerprint)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnalyticsErrorsMutationController::class.java)
    }
}
