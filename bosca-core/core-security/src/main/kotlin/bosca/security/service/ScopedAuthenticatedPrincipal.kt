package bosca.security.service

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.serialization.UUID

/**
 * An [AuthenticatedPrincipal] whose effective permissions are narrowed by an API token's
 * scope and group restrictions.
 *
 * When an API token specifies [allowedGroupIds], group membership checks are filtered to
 * only those groups. When [scopes] are set, permission evaluators should intersect the
 * token's scopes with the entity-level permissions before granting access.
 *
 * This class is transparent to existing authorization code that only calls [hasGroup] —
 * the filtering happens automatically. Code that needs to check scopes can test for this
 * type with `is ScopedAuthenticatedPrincipal`.
 *
 * @param principal the underlying principal record
 * @param allGroups all groups the principal belongs to (before filtering)
 * @param scopes the API token's scope restrictions, or `null` if unrestricted
 * @param allowedGroupIds the API token's group restrictions, or `null` if unrestricted
 * @param credentialId the `principal_credentials.id` of the API token credential, used for audit trails
 */
class ScopedAuthenticatedPrincipal(
    principal: Principal,
    allGroups: List<Group>,
    scopes: List<String>?,
    allowedGroupIds: Set<UUID>?,
    val credentialId: Long,
) : AuthenticatedPrincipal(
    principal,
    if (allowedGroupIds != null) allGroups.filter { it.id in allowedGroupIds } else allGroups
) {

    /** Defensively copied, deduplicated scope set for O(1) lookups. Null means unrestricted. */
    val scopes: Set<String>? = scopes?.toSet()

    /**
     * Returns `true` if this token's scopes include the given scope string.
     * When [scopes] is `null` (unrestricted), always returns `true`.
     */
    fun hasScope(scope: String): Boolean = scopes == null || scope in scopes
}
