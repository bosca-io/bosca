package bosca.feeds.graphql

import bosca.feeds.service.FeedSourceService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID

/**
 * Caller-profile resolution + ownership checks for user-owned feed sources, built from the platform's
 * existing permission primitives ([ProfileService], [GroupEvaluator]) — the same idiom the
 * recommendations module uses. The content `Source` a feed source reuses is not a `PermissibleEntity`,
 * so user-owned sources are authorized by the caller's profile (admins bypass).
 */

/**
 * The authenticated caller's primary profile id — `Principal.primaryProfileId` when set, otherwise their
 * first profile (see [ProfileService.getPrimaryProfile]). Throws when anonymous or the principal has no
 * profile.
 */
suspend fun callerProfileId(authentication: AuthenticationContext, profileService: ProfileService): UUID {
    val principal = authentication.principal()?.asPrincipal() ?: throw SecurityException("authentication is required")
    return profileService.getPrimaryProfile(principal)?.id
        ?: throw SecurityException("the authenticated principal has no profile")
}

/**
 * Verifies the caller may manage user-owned feed source [sourceId] — they own it, or hold the admin
 * group — and returns the source's owner profile id (so edits pin ownership to the rightful owner).
 * Throws [SecurityException] otherwise.
 */
suspend fun verifyFeedSourceOwner(
    authentication: AuthenticationContext,
    sourceId: UUID,
    feedSourceService: FeedSourceService,
    profileService: ProfileService,
    groupEvaluator: GroupEvaluator,
): UUID {
    val source = feedSourceService.get(sourceId) ?: throw SecurityException("feed source $sourceId not found")
    val owner = source.ownerProfileId ?: throw SecurityException("feed source $sourceId is not user-owned")
    if (groupEvaluator.hasAdminGroup(authentication)) return owner
    if (callerProfileId(authentication, profileService) != owner) {
        throw SecurityException("feed source $sourceId is not owned by the caller")
    }
    return owner
}
