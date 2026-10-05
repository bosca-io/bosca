package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.LongCacheKeyPart
import bosca.cache.StringCacheKeyPart
import bosca.cache.UUIDCacheKeyPart
import bosca.cache.UnitCacheKeyPart
import bosca.serialization.UUID
import kotlin.reflect.KClass

object CacheKeyRegistry {

    private val registryByClass = mutableMapOf<KClass<*>, CacheKeySerializer<*>>()
    private val registryByType = mutableMapOf<String, CacheKeySerializer<*>>()

    init {
        register(UUID::class, UUIDCacheKeyPart, UUIDKeySerializer)
        register(Long::class, LongCacheKeyPart, LongKeySerializer)
        register(String::class, StringCacheKeyPart, StringKeySerializer)
        register(Unit::class, UnitCacheKeyPart, UnitKeySerializer)
    }

    fun register(clazz: KClass<*>, type: String, serializer: CacheKeySerializer<*>) {
        registryByType[type] = serializer
        registryByClass[clazz] = serializer
    }

    @Suppress("UNCHECKED_CAST")
    fun <K> toCacheKey(cacheName: String, key: K): CacheKey<K> {
        if (key == null) error("key must not be null")
        val type = key::class
        val serializer = (registryByClass[type] ?: error("No serializer found for $type")) as CacheKeySerializer<K>
        return serializer.toLocalKey(cacheName, key)
    }
}