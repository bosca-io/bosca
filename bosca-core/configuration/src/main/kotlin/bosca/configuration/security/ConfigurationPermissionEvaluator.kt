package bosca.configuration.security

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class ConfigurationPermissionEvaluator(
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
    override val service: ConfigurationService,
) : PermissionEvaluator<Configuration, UUID>() {

    /** Requires security management scope for private configuration access, including decrypted values. */
    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean =
        groupEvaluator.hasScope(authentication, ApiTokenScopes.SECURITY_MANAGE.name)
}
