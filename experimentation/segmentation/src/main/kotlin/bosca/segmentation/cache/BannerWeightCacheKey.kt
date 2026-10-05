package bosca.segmentation.cache

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID

/**
 * Composite key identifying a set of active banner weights for a
 * specific profile and placement combination. A null [profileId]
 * represents anonymous users who only see EVERYONE-targeted banners.
 */
data class BannerWeightKeyId(
    val profileId: UUID?,
    val placement: String
)

data class BannerWeightCacheKey(
    override val cacheName: String,
    override val key: BannerWeightKeyId
) : CacheKey<BannerWeightKeyId> {

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix("bw", cacheName)
        appendKeyPart(key.profileId)
        appendKeyPart(key.placement)
    }
}

@Serializer("bw")
object BannerWeightCacheKeySerializer : CacheKeySerializer<BannerWeightKeyId> {

    override fun toLocalKey(cacheName: String, value: BannerWeightKeyId): CacheKey<BannerWeightKeyId> {
        return BannerWeightCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<BannerWeightKeyId> {
        val keyParts = key.separateForCacheKey()
        val profileId = keyParts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { UUID.parse(it) }
        val placement = keyParts.getOrNull(2) ?: ""
        return BannerWeightCacheKey(keyParts[0], BannerWeightKeyId(profileId, placement))
    }
}
