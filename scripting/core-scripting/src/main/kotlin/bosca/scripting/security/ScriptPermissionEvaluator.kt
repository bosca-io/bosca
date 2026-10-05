package bosca.scripting.security

import bosca.scripting.model.Script
import bosca.scripting.service.ScriptService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates whether a caller has permission to access or execute a specific [Script],
 * applying the standard permission model (public flags, group-based grants, admin overrides).
 */
class ScriptPermissionEvaluator(
    override val service: ScriptService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Script, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.SCRIPTS_VIEW
            PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE -> ApiTokenScopes.SCRIPTS_MANAGE
            PermissionAction.EXECUTE -> ApiTokenScopes.SCRIPTS_EXECUTE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}
