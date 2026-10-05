package bosca.recommendations.graphql

import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID

/**
 * Verifies that the authenticated principal owns the given profile. Admins and
 * service accounts bypass the check. Throws [SecurityException] if the caller
 * is unauthenticated or does not own the profile.
 */
suspend fun verifyProfileOwnership(
    authentication: AuthenticationContext,
    profileId: UUID,
    profileService: ProfileService,
    groupEvaluator: GroupEvaluator,
) {
    if (groupEvaluator.hasAdminGroup(authentication) || groupEvaluator.hasSaGroup(authentication)) return
    val principalId = authentication.principal()?.id ?: throw SecurityException("not authenticated")
    val profile = profileService.getById(profileId)
    if (profile.principal != principalId) {
        throw SecurityException("Unauthorized access")
    }
}
