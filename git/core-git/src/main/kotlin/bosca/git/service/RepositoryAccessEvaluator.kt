package bosca.git.service

import bosca.git.model.Repository
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

/**
 * Read-only repository authorization projection for cross-domain consumers that must explain why
 * an action is unavailable without depending on git's concrete permission-evaluator implementation.
 */
interface RepositoryAccessEvaluator {
    /** Returns whether [authentication] may perform [action] on [repository]. */
    suspend fun isAllowed(
        authentication: AuthenticationContext?,
        entity: Repository,
        action: PermissionAction,
    ): Boolean
}
