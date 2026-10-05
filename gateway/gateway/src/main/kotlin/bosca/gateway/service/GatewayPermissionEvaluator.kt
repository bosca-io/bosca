package bosca.gateway.service

import bosca.gateway.model.Gateway
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class GatewayPermissionEvaluator(
    override val service: GatewayService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<Gateway, UUID>() {

    /** Requires security management scope for private gateway definitions; proxy request scopes do not grant configuration access. */
    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean =
        groupEvaluator.hasScope(authentication, ApiTokenScopes.SECURITY_MANAGE.name)
}
