package bosca.community.service

import bosca.community.repository.CommunityGroupRepository
import bosca.db.transaction
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/** Removes community membership and ACL projections for a deleted or administratively unlinked profile. */
class CommunityProfileCleanupHandler(
    private val communityGroupRepository: CommunityGroupRepository,
    private val securityService: SecurityService,
) : ProfileCleanupHandler {

    override suspend fun onProfileCleanup(profileId: UUID, principalId: UUID?) {
        val communityIds = communityGroupRepository.getGroupsByProfileId(profileId).map { it.id }
        val communitySecurityGroups = principalId?.let { securityService.getPrincipalGroups(it) }.orEmpty()
            .filter { CommunitySecurityGroups.communityId(it.name) != null }

        transaction {
            communityIds.forEach { communityGroupRepository.removeMember(it, profileId) }
            if (principalId != null) {
                communitySecurityGroups.forEach { group ->
                    securityService.removePrincipalGroup(principalId, group.id)
                }
            }
        }
    }
}

internal object CommunitySecurityGroups {
    fun usersName(communityId: UUID): String = "community.$communityId.users"

    fun administratorsName(communityId: UUID): String = "community.$communityId.administrators"

    fun communityId(name: String): UUID? {
        val prefix = "community."
        if (!name.startsWith(prefix)) return null
        val suffix = when {
            name.endsWith(".users") -> ".users"
            name.endsWith(".administrators") -> ".administrators"
            else -> return null
        }
        return runCatching {
            UUID.parse(name.removePrefix(prefix).removeSuffix(suffix))
        }.getOrNull()
    }
}
