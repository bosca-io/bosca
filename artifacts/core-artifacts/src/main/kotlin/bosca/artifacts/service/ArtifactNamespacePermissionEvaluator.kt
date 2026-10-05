package bosca.artifacts.service

import bosca.artifacts.model.ArtifactNamespace
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates access to an [ArtifactNamespace] under the standard platform permission model — public
 * flag, group-based grants, admin/service-account overrides — exactly as bosca-server evaluates any
 * `PermissibleEntity`. Used to authorize JWT/session principals on the registry routes, which otherwise
 * accept only scoped `bsk_` API tokens. The base [PermissionEvaluator] does all the work; this only
 * supplies the namespace [service] (an [ArtifactRepositoryService] is a `PermissionService<ArtifactNamespace, UUID>`).
 */
class ArtifactNamespacePermissionEvaluator(
    override val service: ArtifactRepositoryService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<ArtifactNamespace, UUID>() {

    /** Artifact scopes nest as on the registry routes: `admin` covers `push`, which covers `pull`. */
    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scopes = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST ->
                listOf(ApiTokenScopes.ARTIFACTS_PULL, ApiTokenScopes.ARTIFACTS_PUSH, ApiTokenScopes.ARTIFACTS_ADMIN)
            PermissionAction.EDIT -> listOf(ApiTokenScopes.ARTIFACTS_PUSH, ApiTokenScopes.ARTIFACTS_ADMIN)
            PermissionAction.DELETE, PermissionAction.MANAGE -> listOf(ApiTokenScopes.ARTIFACTS_ADMIN)
            else -> return false
        }
        return scopes.any { groupEvaluator.hasScope(authentication, it.name) }
    }
}
