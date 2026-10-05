package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.StringCacheKey
import bosca.cache.StringCacheKeyPart
import bosca.cache.annotations.Serializer

@Serializer(StringCacheKeyPart)
object StringKeySerializer : CacheKeySerializer<String> {

    override fun toLocalKey(cacheName: String, value: String): CacheKey<String> = StringCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<String> {
        val parts = key.separateForCacheKey()
        return StringCacheKey(parts[0], parts[1])
    }
}