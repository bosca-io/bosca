package bosca.analytics.graphql

import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Shared authorization checks for the error tracking GraphQL
 * controllers. Centralizes the `analytics.manager` / admin group
 * verification so read and mutation controllers stay in sync when
 * requirements change.
 */
internal object AnalyticsErrorsAuthorization {

    /**
     * Verifies the caller has at least read access to the error
     * tracking subsystem. Requires either the `analytics.manager`
     * group or platform admin.
     *
     * The explicit `hasAdminGroup` check is kept alongside `hasGroup`
     * so that callers / tests can stub the two independently. In
     * production `GroupEvaluator.hasGroup` already falls through to
     * "administrators", but mocks don't inherit that fallback.
     */
    fun verifyCanRead(
        groupEvaluator: GroupEvaluator,
        authentication: AuthenticationContext,
    ) {
        val canRead = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canRead) {
            groupEvaluator.throwUnauthorized()
        }
    }

    /**
     * Verifies the caller can perform mutations (status changes,
     * assignments, AI analysis) on error groups. Currently identical
     * to [verifyCanRead] — separated so the two can diverge later
     * (e.g., read-only viewer role).
     */
    fun verifyCanManage(
        groupEvaluator: GroupEvaluator,
        authentication: AuthenticationContext,
    ) {
        val canManage = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!canManage) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
