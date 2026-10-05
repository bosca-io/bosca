package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.LongCacheKey
import bosca.cache.LongCacheKeyPart
import bosca.cache.annotations.Serializer

@Serializer(LongCacheKeyPart)
object LongKeySerializer : CacheKeySerializer<Long> {

    override fun toLocalKey(cacheName: String, value: Long): CacheKey<Long> = LongCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<Long> {
        val parts = key.separateForCacheKey()
        return LongCacheKey(parts[0], parts[1].toLong())
    }
}