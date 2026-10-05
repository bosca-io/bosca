package bosca.cache.redis

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheValue
import bosca.cache.LongCacheKey
import bosca.cache.serializers.LongKeySerializer
import bosca.redis.RedisConnectionPool
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Duration

@Suppress("OPT_IN_USAGE")
@OptIn(ExperimentalSerializationApi::class)
class RedisCache<K>(
    @Suppress("UNUSED_PARAMETER") connections: RedisConnectionPool,
    private val scripts: RedisCacheScripts,
    private val cacheName: String,
    private val expiration: Duration,
    override val keySerializer: CacheKeySerializer<K>
) : Cache<K> {

    private class RedisCacheValue(
        override val value: String?,
        override val exists: Boolean
    ) : CacheValue

    private val evictionsChannel = "$cacheName:evictions"
    private val evictionsAllChannel = "$cacheName:evictions:all"

    override val estimatedSize: Long
        get() = -1

    override suspend fun get(key: CacheKey<K>): CacheValue = getBatch(listOf(key)).single()

    override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> {
        if (keys.isEmpty()) {
            return emptyList()
        }
        return scripts.getBatch(cacheName, evictionsChannel, keySerializer, keys)
            .map { (exists, value) -> RedisCacheValue(value, exists) }
    }

    override suspend fun getAndTouch(key: CacheKey<K>): CacheValue = getBatchAndTouch(listOf(key)).single()

    override suspend fun getBatchAndTouch(keys: List<CacheKey<K>>): List<CacheValue> {
        return scripts.getAndTouchBatch(
            cacheName,
            evictionsChannel,
            keySerializer,
            keys,
            expiration.inWholeMilliseconds,
        )
            .map { (exists, value) -> RedisCacheValue(value, exists) }
    }

    override suspend fun put(key: CacheKey<K>, value: String?) {
        scripts.put(cacheName, evictionsChannel, keySerializer, key, expiration.inWholeMilliseconds, value)
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) {
        if (entries.isEmpty()) {
            return
        }
        if (entries.size == 1) {
            val (key, value) = entries.first()
            put(key, value)
            return
        }
        val cache = mutableListOf<Triple<CacheKey<K>, Long, String>>()
        entries.forEach { (key, value) ->
            cache.add(Triple(key, expiration.inWholeMilliseconds, value ?: ""))
        }
        scripts.putBatch(cacheName, evictionsChannel, keySerializer, cache)
    }

    override suspend fun putIfAbsent(key: CacheKey<K>, value: String): Boolean {
        return scripts.putIfAbsent(
            cacheName,
            evictionsChannel,
            keySerializer,
            key,
            value,
            expiration.inWholeMilliseconds,
        )
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? {
        if (keyPrefix) {
            scripts.removePrefix(cacheName, evictionsChannel, keySerializer, key)
            return null
        } else {
            val removed = scripts.remove(cacheName, evictionsChannel, keySerializer, key)?.let { RedisCacheValue(it, true) }
            return removed
        }
    }

    override suspend fun removeBatch(keys: List<CacheKey<K>>, prefixes: List<CacheKey<K>>) {
        scripts.removeBatch(cacheName, evictionsChannel, keySerializer, keys, prefixes)
    }

    override suspend fun clear() {
        scripts.clear(
            cacheName,
            evictionsAllChannel,
            LongKeySerializer.toRemoteKey(LongCacheKey("*", 0)),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun evictExpiredItems() {
        scripts.expiration(cacheName, evictionsAllChannel)
    }
}
