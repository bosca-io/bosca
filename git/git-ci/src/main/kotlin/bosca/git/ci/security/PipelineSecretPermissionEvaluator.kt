package bosca.git.ci.security

import bosca.git.model.PipelineSecret
import bosca.git.service.PipelineSecretService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Standard Bosca permission evaluator for [PipelineSecret] entities: explicit group
 * grants on the secret itself, with the owning repository's permissions as the parent fallback —
 * evaluated against the run's initiating principal at resolution time.
 */
class PipelineSecretPermissionEvaluator(
    override val service: PipelineSecretService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<PipelineSecret, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.CI_READ
            PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.MANAGE -> ApiTokenScopes.CI_MANAGE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}
