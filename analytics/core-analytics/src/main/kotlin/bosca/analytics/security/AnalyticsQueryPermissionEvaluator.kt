package bosca.analytics.security

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.service.AnalyticsQueryService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class AnalyticsQueryPermissionEvaluator(
    override val service: AnalyticsQueryService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<AnalyticsQuery, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.ANALYTICS_VIEW
            PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE -> ApiTokenScopes.ANALYTICS_MANAGE
            PermissionAction.EXECUTE -> ApiTokenScopes.ANALYTICS_EXECUTE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }

    override fun hasRoleBasedAccess(authentication: AuthenticationContext, action: PermissionAction): Boolean {
        if (groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP)) {
            return true
        }
        return super.hasRoleBasedAccess(authentication, action)
    }
}
