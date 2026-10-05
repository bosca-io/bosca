package bosca.git.security

import bosca.git.model.Repository
import bosca.git.service.RepositoryAccessEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates access control for git repositories using the standard Bosca permission
 * evaluation flow with git and CI scope awareness.
 *
 * - **SA group** → full access on any action, regardless of scope.
 * - **Unrestricted admin** (admin group with no scope restrictions on the token)
 *   → full access on any action.
 * - **Unrestricted editors/managers** → EXECUTE on a repository, alongside the EDIT
 *   the base evaluator grants them. System-level checks stay admin-only.
 * - **CI scopes** (`ci:read`, `ci:edit`, `ci:execute`, `ci:manage`) → grant the
 *   corresponding action **without any group membership requirement**. CI tokens
 *   are machine credentials and don't carry human-role groups. One scope per
 *   action: `ci:edit` is repository write (e.g., updating source refs after a
 *   pipeline run), distinct from `ci:execute` which is pipeline execution.
 * - **Git scopes** (`git:read`, `git:write`, `git:manage`) → require the principal
 *   to also be in the `editors` or `managers` group (or the admin group, in which
 *   case any matching scope is sufficient). MANAGE via a git scope is reserved
 *   for the admin group.
 * - **System-level checks** ([verifyAllowed], with no repository) → an admin token
 *   additionally needs `security:manage`, because operations such as agent
 *   registration mint new credentials.
 */
class RepositoryPermissionEvaluator(
    override val service: RepositoryService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Repository, UUID>(), RepositoryAccessEvaluator {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scopes = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> listOf(ApiTokenScopes.GIT_READ, ApiTokenScopes.CI_READ)
            PermissionAction.EDIT -> listOf(ApiTokenScopes.GIT_WRITE, ApiTokenScopes.CI_EDIT)
            PermissionAction.MANAGE -> listOf(ApiTokenScopes.GIT_MANAGE, ApiTokenScopes.CI_MANAGE)
            PermissionAction.DELETE -> listOf(ApiTokenScopes.GIT_MANAGE)
            PermissionAction.EXECUTE -> listOf(ApiTokenScopes.CI_EXECUTE)
            PermissionAction.IMPERSONATE -> return false
        }
        return scopes.any { groupEvaluator.hasScope(authentication, it.name) }
    }

    /**
     * Verifies that the caller has role-based access for the given action
     * without requiring a specific repository entity. Used for system-level
     * operations like agent management. Admin tokens additionally need
     * `security:manage`; a git scope alone does not grant system-level access.
     */
    fun verifyAllowed(authentication: AuthenticationContext, action: PermissionAction) {
        if (!hasRoleBasedAccess(authentication, action, systemLevel = true)) {
            throw SecurityException("Access denied")
        }
    }

    override fun hasRoleBasedAccess(authentication: AuthenticationContext, action: PermissionAction): Boolean =
        hasRoleBasedAccess(authentication, action, systemLevel = false)

    private fun hasRoleBasedAccess(
        authentication: AuthenticationContext,
        action: PermissionAction,
        systemLevel: Boolean,
    ): Boolean {
        // SA group → full access regardless of token scope.
        if (groupEvaluator.hasSaGroup(authentication)) return true

        val principal = authentication.principal() as? ScopedAuthenticatedPrincipal
        // Unrestricted admin token (scopes == null) → full access. This is the
        // normal browser-session case for admins. Editors and managers, who hold
        // EDIT on every repository, may also execute that repository's pipelines.
        if (principal == null || principal.scopes == null) {
            if (groupEvaluator.hasAdminGroup(authentication)) return true
            if (systemLevel || action != PermissionAction.EXECUTE) return false
            val unscoped = authentication.principal() ?: return false
            return unscoped.hasGroup("editors") || unscoped.hasGroup("managers")
        }

        // Scoped tokens beyond this point.

        // CI scopes are machine credentials and grant access without any group
        // membership check. They cover repository-level actions a CI agent
        // legitimately needs to perform: reading a repo to clone it, executing
        // a pipeline against it, managing its CI configuration. Notably,
        // `ci:execute` maps to PermissionAction.EXECUTE (pipeline runs) — *not*
        // to EDIT, which is repository write access. A CI token that needs to
        // write back to a repository must carry `ci:edit`; an ordinary Git token
        // instead needs `git:write` plus an administrator/editor/manager role.
        val ciScopeAllows = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> principal.hasScope("ci:read")
            PermissionAction.EDIT -> principal.hasScope("ci:edit")
            PermissionAction.EXECUTE -> principal.hasScope("ci:execute")
            PermissionAction.MANAGE -> principal.hasScope("ci:manage")
            else -> false
        }
        if (ciScopeAllows) return true

        // Git scopes require either the admin group (any action) or the
        // editors/managers group (non-MANAGE actions only). System-level checks
        // keep the full admin requirement, including `security:manage`.
        val gitScopeAllows = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> principal.hasScope("git:read")
            PermissionAction.EDIT -> principal.hasScope("git:write")
            PermissionAction.MANAGE -> principal.hasScope("git:manage")
            else -> false
        }
        if (!gitScopeAllows) return false

        val isAdmin = if (systemLevel) {
            groupEvaluator.hasAdminGroup(authentication)
        } else {
            principal.hasGroup("administrators")
        }
        if (isAdmin) return true
        if (action == PermissionAction.MANAGE) return false
        return principal.hasGroup("editors") || principal.hasGroup("managers")
    }
}
