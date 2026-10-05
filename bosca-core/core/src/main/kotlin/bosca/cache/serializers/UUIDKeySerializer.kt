package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.UUIDCacheKey
import bosca.cache.UUIDCacheKeyPart
import bosca.cache.annotations.Serializer
import bosca.serialization.UUID


@Serializer(UUIDCacheKeyPart)
object UUIDKeySerializer : CacheKeySerializer<UUID> {

    override fun toLocalKey(cacheName: String, value: UUID): CacheKey<UUID> = UUIDCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<UUID> {
        val parts = key.separateForCacheKey()
        return UUIDCacheKey(parts[0], UUID.parse(parts[1]))
    }
}