package bosca.profile.security

import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class OrganizationPermissionEvaluator(
    override val service: OrganizationService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Organization, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.PROFILES_READ
            PermissionAction.EDIT -> ApiTokenScopes.PROFILES_EDIT
            PermissionAction.DELETE -> ApiTokenScopes.PROFILES_DELETE
            PermissionAction.MANAGE -> ApiTokenScopes.PROFILES_MANAGE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}