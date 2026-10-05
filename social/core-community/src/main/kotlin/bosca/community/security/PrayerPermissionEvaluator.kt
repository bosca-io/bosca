package bosca.community.security

import bosca.community.model.Prayer
import bosca.community.service.PrayerService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class PrayerPermissionEvaluator(
    override val service: PrayerService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Prayer, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.COMMUNITY_READ
            PermissionAction.EDIT -> ApiTokenScopes.COMMUNITY_EDIT
            PermissionAction.DELETE, PermissionAction.MANAGE -> ApiTokenScopes.COMMUNITY_MANAGE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}
