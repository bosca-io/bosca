package bosca.profile.security

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class ProfilePermissionEvaluator(
    override val service: ProfileService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<Profile, UUID>() {

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

    /** Returns whether the caller may manage relationship requests for [profile]. */
    suspend fun canManageRelationships(
        authentication: AuthenticationContext?,
        profile: Profile,
    ): Boolean {
        if (profile.isDeleted) return false
        if (groupEvaluator.hasSaGroup(authentication)) return true
        if (!groupEvaluator.hasScope(authentication, ApiTokenScopes.PROFILES_EDIT.name)) return false
        val principal = authentication?.principal() ?: return false
        if (profile.principal == principal.id) return true
        return service.getPermissions(profile).any {
            it.action == PermissionAction.MANAGE && principal.hasGroup(it.groupId)
        }
    }

    /** Verifies that the caller may manage relationship requests for [profile]. */
    suspend fun verifyCanManageRelationships(
        authentication: AuthenticationContext?,
        profile: Profile,
    ) {
        if (!canManageRelationships(authentication, profile)) {
            throwUnauthorized()
        }
    }

    /** Verifies that the caller manages at least one of the two relationship participants. */
    suspend fun verifyCanManageEitherRelationshipParticipant(
        authentication: AuthenticationContext?,
        first: Profile,
        second: Profile,
    ) {
        if (!canManageRelationships(authentication, first) &&
            !canManageRelationships(authentication, second)
        ) {
            throwUnauthorized()
        }
    }
}
