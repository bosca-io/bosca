package bosca.profile.relationship.service

import bosca.cache.ServiceCache
import bosca.profile.relationship.cache.ProfileRelationshipCacheKeyId
import bosca.profile.relationship.cache.ProfileRelationshipCacheKeySerializer
import bosca.profile.relationship.events.ProfileRelationshipAdded
import bosca.profile.relationship.events.ProfileRelationshipRemoved
import bosca.profile.relationship.events.dispatch
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.repository.ProfileRelationshipRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class ProfileRelationshipServiceImpl(
    private val profileRelationshipRepository: ProfileRelationshipRepository
) : ProfileRelationshipService {

    private val relationshipByKey = ServiceCache(
        cacheName = "profile_relationship:exact",
        serializer = ProfileRelationshipCacheKeySerializer,
    ) { key ->
        profileRelationshipRepository.getRelationship(key.profileId1, key.profileId2, key.type)
    }

    override suspend fun getRelationship(
        profileId1: UUID,
        profileId2: UUID,
        type: String,
    ): ProfileRelationship? = relationshipByKey.get(cacheKey(profileId1, profileId2, type))

    override suspend fun getRelationships(profileId: UUID, type: String?): List<ProfileRelationship> {
        return getRelationships(profileId, type, offset = 0, limit = Int.MAX_VALUE)
    }

    override suspend fun getRelationships(
        profileId: UUID,
        type: String?,
        offset: Long,
        limit: Int,
    ): List<ProfileRelationship> {
        require(offset >= 0) { "offset must not be negative" }
        require(limit >= 0) { "limit must not be negative" }
        return if (type == null) {
            profileRelationshipRepository.getRelationships(profileId, offset, limit)
        } else {
            profileRelationshipRepository.getRelationshipsByType(profileId, type, offset, limit)
        }
    }

    override suspend fun addRelationship(
        profileId1: UUID,
        profileId2: UUID,
        type: String,
        attributes: JsonElement?
    ) {
        require(profileId1 != profileId2) { "a profile cannot have a relationship with itself" }
        val inserted = profileRelationshipRepository.addRelationship(profileId1, profileId2, type, attributes) > 0
        if (!inserted) {
            check(profileRelationshipRepository.updateRelationship(profileId1, profileId2, type, attributes) == 1) {
                "relationship disappeared during update"
            }
        }
        relationshipByKey.remove(cacheKey(profileId1, profileId2, type))
        if (inserted) {
            ProfileRelationshipAdded(profileId1, profileId2, type).dispatch()
        }
    }

    override suspend fun removeRelationship(profileId1: UUID, profileId2: UUID, type: String) {
        profileRelationshipRepository.removeRelationship(profileId1, profileId2, type)
        relationshipByKey.remove(cacheKey(profileId1, profileId2, type))
        ProfileRelationshipRemoved(profileId1, profileId2, type).dispatch()
    }

    private fun cacheKey(profileId1: UUID, profileId2: UUID, type: String) =
        ProfileRelationshipCacheKeyId(profileId1, profileId2, type)
}
