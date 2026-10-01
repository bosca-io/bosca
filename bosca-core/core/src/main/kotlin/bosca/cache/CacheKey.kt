package bosca.cache

import bosca.cache.serializers.buildCacheKey
import bosca.serialization.UUID

/**
 * Represents a typed cache key scoped to a named cache.
 *
 * Knows how to serialize itself into both a full remote-store key string and a prefix
 * form used for prefix-based eviction.
 *
 * @param K the application-level key type (e.g. UUID, String, Long, Unit)
 */
interface CacheKey<K> {

    /** The logical cache name this key belongs to. */
    val cacheName: String

    /** The typed key value. */
    val key: K

    /**
     * Produces the remote-store key string. When [prefix] is `true`, returns just
     * the prefix portion (cache name + type tag) without the key value itself.
     */
    fun toRemoteKey(prefix: Boolean = false): String

    /** Shorthand for [toRemoteKey] with `prefix = true`. */
    fun toRemoteKeyPrefix(): String = toRemoteKey(true)
}

const val UUIDCacheKeyPart = "uuid"
const val LongCacheKeyPart = "lng"
const val StringCacheKeyPart = "str"
const val UnitCacheKeyPart = "unit"

data class UUIDCacheKey(
    override val cacheName: String,
    override val key: UUID
) : CacheKey<UUID> {

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix(UUIDCacheKeyPart, cacheName)
        appendKeyPart(key)
    }
}

data class StringCacheKey(
    override val cacheName: String,
    override val key: String
) : CacheKey<String> {

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix(StringCacheKeyPart, cacheName)
        appendKeyPart(key)
    }
}

data class LongCacheKey(
    override val cacheName: String,
    override val key: Long
) : CacheKey<Long> {

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix(LongCacheKeyPart, cacheName)
        appendKeyPart(key)
    }
}

data class UnitCacheKey(
    override val cacheName: String
) : CacheKey<Unit> {

    override val key: Unit = Unit

    override fun toRemoteKey(prefix: Boolean) = buildCacheKey(prefix) {
        appendKeyPrefix(UnitCacheKeyPart, cacheName)
        appendKeyPart(key)
    }
}
