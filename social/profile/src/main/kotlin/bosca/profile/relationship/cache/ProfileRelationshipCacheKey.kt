package bosca.profile.relationship.cache

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID
import java.util.Base64

/** Identifies one exact directional relationship for caching. */
data class ProfileRelationshipCacheKeyId(
    val profileId1: UUID,
    val profileId2: UUID,
    val type: String,
)

/** Cache key for one exact directional relationship. */
data class ProfileRelationshipCacheKey(
    override val cacheName: String,
    override val key: ProfileRelationshipCacheKeyId,
) : CacheKey<ProfileRelationshipCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix = false) {
        appendKeyPrefix(PROFILE_RELATIONSHIP_CACHE_KEY_PART, cacheName)
        appendKeyPart(key.profileId1)
        appendKeyPart(encodeType(key.type))
        // Keeping the target last makes the prefix source + type, so all targets for that type
        // can be invalidated with one prefix scan.
        appendKeyPart(if (prefix) "" else key.profileId2)
    }
}

/** Serializes exact relationship cache keys without exposing relationship types to key separators. */
@Serializer(PROFILE_RELATIONSHIP_CACHE_KEY_PART)
object ProfileRelationshipCacheKeySerializer : CacheKeySerializer<ProfileRelationshipCacheKeyId> {

    override fun toLocalKey(
        cacheName: String,
        value: ProfileRelationshipCacheKeyId,
    ): CacheKey<ProfileRelationshipCacheKeyId> = ProfileRelationshipCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<ProfileRelationshipCacheKeyId> {
        val parts = key.separateForCacheKey()
        return ProfileRelationshipCacheKey(
            cacheName = parts[0],
            key = ProfileRelationshipCacheKeyId(
                profileId1 = UUID.parse(parts[1]),
                profileId2 = UUID.parse(parts[3]),
                type = decodeType(parts[2]),
            ),
        )
    }
}

private const val PROFILE_RELATIONSHIP_CACHE_KEY_PART = "prr"
private const val ENCODED_TYPE_PREFIX = "b"

private fun encodeType(type: String): String =
    ENCODED_TYPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(type.encodeToByteArray())

private fun decodeType(type: String): String =
    Base64.getUrlDecoder().decode(type.removePrefix(ENCODED_TYPE_PREFIX)).decodeToString()
