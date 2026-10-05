package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal

/**
 * Evaluates group-based and scope-based authorization for authenticated principals.
 *
 * When the principal is a [ScopedAuthenticatedPrincipal] (i.e. authenticated via an API token),
 * every group check also verifies that the token carries the required scope. This ensures that
 * scoped API tokens cannot exceed their declared permissions even if the underlying principal
 * has broader group membership.
 */
class GroupEvaluator(
    override val securityService: SecurityService
) : SecurityEvaluator() {

    override val groupEvaluator: GroupEvaluator
        get() = this

    fun verifyHasGroup(authentication: AuthenticationContext?, group: String) {
        if (!hasGroup(authentication, group)) {
            throwUnauthorized()
        }
    }

    /** Verifies that the caller is authenticated and its API token permits [scope]. */
    fun verifyHasScope(authentication: AuthenticationContext?, scope: String) {
        if (!hasScope(authentication, scope)) {
            throwUnauthorized()
        }
    }

    /**
     * Returns whether the caller is authenticated and permitted to use [scope].
     * Session and other non-token principals are unrestricted by API-token scopes.
     */
    fun hasScope(authentication: AuthenticationContext?, scope: String): Boolean {
        val principal = authentication?.principal() ?: return false
        return hasRequiredScope(principal, scope)
    }

    fun verifyHasAdminGroup(authentication: AuthenticationContext?) {
        if (!hasAdminGroup(authentication)) {
            throwUnauthorized()
        }
    }

    fun hasAdminGroup(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        if (!principal.hasGroup("administrators")) return false
        return hasRequiredScope(principal, "security:manage")
    }

    fun verifyHasEditorGroup(authentication: AuthenticationContext?) {
        if (!hasEditorGroup(authentication)) {
            throwUnauthorized()
        }
    }

    fun hasEditorGroup(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        val hasGroup = principal.hasGroup("editors") || principal.hasGroup("administrators") || principal.hasGroup("sa") || principal.hasGroup("managers")
        if (!hasGroup) return false
        return hasRequiredScope(principal, "content:edit")
    }

    fun verifyHasManagerGroup(authentication: AuthenticationContext?) {
        if (!hasManagerGroup(authentication)) {
            throwUnauthorized()
        }
    }

    fun hasManagerGroup(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        val hasGroup = principal.hasGroup("administrators") || principal.hasGroup("managers")
        if (!hasGroup) return false
        return hasRequiredScope(principal, "content:manage")
    }

    /** Verifies that the request belongs to an authenticated account. */
    fun verifyHasMessagingAccess(authentication: AuthenticationContext?) {
        if (!hasMessagingAccess(authentication)) {
            throwUnauthorized()
        }
    }

    /** Messaging access is account-level and does not require a security group or API-token scope. */
    fun hasMessagingAccess(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        return authentication.principal() != null
    }

    fun verifyHasMessagingGroup(authentication: AuthenticationContext?) {
        if (!hasMessagingGroup(authentication)) {
            throwUnauthorized()
        }
    }

    fun hasMessagingGroup(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        val hasGroup = principal.hasGroup("messaging") || principal.hasGroup("administrators") || principal.hasGroup("sa")
        if (!hasGroup) return false
        return hasRequiredScope(principal, "messaging:use")
    }

    fun verifyHasSaGroup(authentication: AuthenticationContext?) {
        if (!hasSaGroup(authentication)) {
            throwUnauthorized()
        }
    }

    fun hasSaGroup(authentication: AuthenticationContext?): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        val hasGroup = principal.hasGroup("sa") || principal.hasGroup("administrators")
        if (!hasGroup) return false
        return hasRequiredScope(principal, "security:manage")
    }

    fun hasGroup(authentication: AuthenticationContext?, group: String): Boolean {
        val authentication = authentication ?: return false
        val principal = authentication.principal() ?: return false
        val hasGroup = principal.hasGroup(group) || principal.hasGroup("administrators")
        if (!hasGroup) return false
        return hasRequiredScope(principal, groupToScope(group))
    }

    /**
     * Checks whether a [ScopedAuthenticatedPrincipal] carries the required scope.
     * For non-scoped principals (JWT, Basic auth), always returns `true` — scopes
     * only restrict API token sessions.
     */
    private fun hasRequiredScope(principal: AuthenticatedPrincipal, scope: String): Boolean {
        if (principal !is ScopedAuthenticatedPrincipal) return true
        return principal.hasScope(scope)
    }

    companion object {

        /**
         * Maps a group name to the minimum scope required to act under that group's authority.
         *
         * Groups without a specific mapping default to requiring `security:manage`, which is
         * the most restrictive scope — this ensures that unknown or custom groups are not
         * accidentally accessible to narrowly-scoped tokens. To grant API token access for
         * a custom group, add an explicit mapping here.
         */
        fun groupToScope(group: String): String = when (group) {
            "editors" -> "content:edit"
            "managers" -> "content:manage"
            "administrators" -> "security:manage"
            "sa" -> "security:manage"
            "viewers" -> "content:view"
            "community.moderators" -> "community:manage"
            "analysts" -> "analytics:view"
            "analytics.manager" -> "analytics:manage"
            "messaging" -> "messaging:use"
            "mcp" -> "mcp:execute"
            else -> "security:manage"
        }
    }
}
