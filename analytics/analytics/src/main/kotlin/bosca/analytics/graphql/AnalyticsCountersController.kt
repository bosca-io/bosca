package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.service.CounterMetricsService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Marker type for the `Analytics.counters` GraphQL field — the entry point to distributed counter telemetry. */
object AnalyticsCounters

/**
 * Read-side GraphQL controller for distributed counter telemetry (active sessions, API response-code
 * rates). Counter data is platform-wide operational health, not a per-resource entity, so access is gated
 * by the `analytics.manager` group (or platform admin) — the same read gate the error tracking subsystem
 * uses — rather than a per-object permission evaluator.
 */
@TypeController
class AnalyticsCountersController(
    private val counterMetricsService: CounterMetricsService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AnalyticsCounters> {

    @Field
    suspend fun byId(authentication: AuthenticationContext, id: String): AnalyticsCounter {
        verifyCanRead(authentication)
        return counterMetricsService.counter(id)
            ?: error("Unknown counter: $id")
    }

    /** Requires the `analytics.manager` group or platform admin; throws otherwise. */
    private fun verifyCanRead(authentication: AuthenticationContext) {
        val canRead = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canRead) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
