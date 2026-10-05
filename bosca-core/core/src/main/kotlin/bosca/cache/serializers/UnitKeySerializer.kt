package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.UnitCacheKey
import bosca.cache.UnitCacheKeyPart
import bosca.cache.annotations.Serializer


@Serializer(UnitCacheKeyPart)
object UnitKeySerializer : CacheKeySerializer<Unit> {

    override fun toLocalKey(cacheName: String, value: Unit): CacheKey<Unit> = UnitCacheKey(cacheName)

    override fun fromRemoteKey(key: String): CacheKey<Unit> {
        val parts = key.separateForCacheKey()
        return UnitCacheKey(parts[0])
    }
}