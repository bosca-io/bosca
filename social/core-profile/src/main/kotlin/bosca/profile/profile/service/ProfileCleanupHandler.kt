package bosca.profile.profile.service

import bosca.serialization.UUID

/**
 * Performs idempotent, domain-owned cleanup after a profile permanently loses its identity.
 * This happens when the profile is hard-deleted or administratively unlinked from its principal.
 * Implementations are discovered by the durable profile cleanup job.
 */
interface ProfileCleanupHandler {

    /** Removes data and memberships owned by [profileId] and access held by [principalId]. */
    suspend fun onProfileCleanup(profileId: UUID, principalId: UUID?)
}
