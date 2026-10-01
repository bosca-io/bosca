package bosca.content.metadata.service

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID

data class BibleCacheKeyId(
    val id: UUID,
    val version: Int? = null,
    val variant: String? = null,
    val usfm: String? = null,
)

data class BibleCacheKey(
    override val cacheName: String,
    override val key: BibleCacheKeyId
) : CacheKey<BibleCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("bck", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.version)
        appendKeyPart(key.variant)
        appendKeyPart(key.usfm)
    }
}

@Serializer("bck")
object BibleCacheKeySerializer : CacheKeySerializer<BibleCacheKeyId> {

    override fun toLocalKey(cacheName: String, value: BibleCacheKeyId): CacheKey<BibleCacheKeyId> {
        return BibleCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<BibleCacheKeyId> {
        val keyParts = key.separateForCacheKey()
        val id = UUID.parse(keyParts[1])
        val version = keyParts.getOrNull(2)?.toIntOrNull()
        val variant = keyParts.getOrNull(3)
        val usfm = keyParts.getOrNull(4)
        return BibleCacheKey(keyParts.first(), BibleCacheKeyId(id, version, variant, usfm))
    }
}
