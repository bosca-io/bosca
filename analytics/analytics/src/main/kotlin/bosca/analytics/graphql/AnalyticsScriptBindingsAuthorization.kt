package bosca.analytics.graphql

import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Shared authorization checks for the analytics script-binding GraphQL controllers. Requires the
 * `analytics.manager` group or platform admin, mirroring the error-tracking controllers so the read
 * and mutation surfaces stay in sync.
 */
internal object AnalyticsScriptBindingsAuthorization {

    /** Verifies the caller may read script bindings. */
    fun verifyCanRead(groupEvaluator: GroupEvaluator, authentication: AuthenticationContext) =
        verify(groupEvaluator, authentication)

    /** Verifies the caller may create, edit, or delete script bindings. */
    fun verifyCanManage(groupEvaluator: GroupEvaluator, authentication: AuthenticationContext) =
        verify(groupEvaluator, authentication)

    private fun verify(groupEvaluator: GroupEvaluator, authentication: AuthenticationContext) {
        val allowed = groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!allowed) {
            groupEvaluator.throwUnauthorized()
        }
    }
}
