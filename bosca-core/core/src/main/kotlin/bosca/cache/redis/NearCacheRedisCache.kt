package bosca.cache.redis

import bosca.cache.Cache
import bosca.cache.CacheDispatcher
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheValue
import bosca.cache.LongCacheKey
import bosca.cache.serializers.LongKeySerializer
import bosca.redis.RedisConnectionPool
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Suppress("OPT_IN_USAGE")
@OptIn(ExperimentalSerializationApi::class)
class NearCacheRedisCache<K>(
    private val connections: RedisConnectionPool,
    private val scripts: RedisCacheScripts,
    private val cacheName: String,
    private val expiration: Duration,
    override val keySerializer: CacheKeySerializer<K>,
    lifecycleScope: CoroutineScope,
) : Cache<K>, AutoCloseable {

    init {
        // Checked before the subscriptions below start. Without publishes, other nodes' near caches never learn of
        // changes and would serve stale values until their local entries expire.
        require(scripts.publishEvictions) { "NearCacheRedisCache requires RedisCacheScripts that publish evictions" }
    }

    constructor(
        connections: RedisConnectionPool,
        scripts: RedisCacheScripts,
        cacheName: String,
        expiration: Duration,
        keySerializer: CacheKeySerializer<K>,
    ) : this(
        connections,
        scripts,
        cacheName,
        expiration,
        keySerializer,
        CoroutineScope(SupervisorJob() + CacheDispatcher),
    )

    private class RedisCacheValue(
        override val value: String?,
        override val exists: Boolean
    ) : CacheValue

    private val evictionsChannel = "$cacheName:evictions"
    private val evictionsAllChannel = "$cacheName:evictions:all"

    private val nearCache = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .build<CacheKey<K>, RedisCacheValue>()

    private val lifecycleJobs: List<Job> = listOf(
        lifecycleScope.launch(CacheDispatcher) {
            subscribe(evictionsChannel).collect {
                @Suppress("UNCHECKED_CAST")
                nearCache.invalidate(it as CacheKey<K>)
            }
        },
        lifecycleScope.launch(CacheDispatcher) {
            subscribe(evictionsAllChannel).collect {
                nearCache.invalidateAll()
            }
        },
        lifecycleScope.launch(CacheDispatcher) {
            while (currentCoroutineContext().isActive) {
                try {
                    evictExpiredItems()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Failed to evict expired items: {}", e.message, e)
                }
                delay(30_000.milliseconds)
            }
        },
    )

    private fun subscribe(channel: String) = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val connection = connections.newPubSubConnection()
                try {
                    val reactive = connection.reactive()
                    reactive.subscribe(channel).awaitFirstOrNull()
                    while (currentCoroutineContext().isActive) {
                        val channels = reactive.observeChannels().asFlow()
                        channels.collect {
                            if (channel == evictionsAllChannel) {
                                emit(LongCacheKey(cacheName, 0))
                            } else {
                                val key = keySerializer.fromRemoteKey(it.message)
                                emit(key)
                            }
                        }
                    }
                } finally {
                    connection.closeAsync().await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to subscribe to channel $channel: ${e.message}", e)
                if (e.message == "Disconnected") {
                    continue
                }
                delay(10)
                nearCache.invalidateAll()
            }
        }
    }.flowOn(CacheDispatcher)

    override val estimatedSize: Long
        get() = nearCache.estimatedSize()

    override suspend fun get(key: CacheKey<K>): CacheValue {
        nearCache.getIfPresent(key)?.let {
            return it
        }
        val (exists, value) = scripts.getBatch(cacheName, evictionsChannel, keySerializer, listOf(key)).single()
        val localCache = RedisCacheValue(value, exists)
        nearCache.put(key, localCache)
        return localCache
    }

    override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> {
        val map = linkedMapOf<CacheKey<K>, CacheValue>()
        val localKeys = keys.map { it }
        val localCache = nearCache.getAllPresent(localKeys)
        if (localCache.size == keys.size) {
            return localKeys.map { localCache.getValue(it) }
        }
        val missingKeys = ArrayList<CacheKey<K>>(keys.size)
        for (key in keys) {
            localCache[key]?.let {
                map[key] = it
            } ?: run {
                missingKeys.add(key)
            }
        }
        if (missingKeys.isNotEmpty()) {
            val results = scripts.getBatch(cacheName, evictionsChannel, keySerializer, missingKeys)
            results.forEachIndexed { index, result ->
                val key = missingKeys[index]
                val (exists, value) = result
                val newValue = RedisCacheValue(value, exists)
                map[key] = newValue
                nearCache.put(key, newValue)
            }
        }
        return keys.map { map.getValue(it) }
    }

    override suspend fun getAndTouch(key: CacheKey<K>): CacheValue = getBatchAndTouch(listOf(key)).single()

    override suspend fun getBatchAndTouch(keys: List<CacheKey<K>>): List<CacheValue> {
        return scripts.getAndTouchBatch(
            cacheName,
            evictionsChannel,
            keySerializer,
            keys,
            expiration.toMillis(),
        ).mapIndexed { index, result ->
            val (exists, value) = result
            RedisCacheValue(value, exists).also { nearCache.invalidate(keys[index]) }
        }
    }

    override suspend fun put(key: CacheKey<K>, value: String?) {
        nearCache.put(key, RedisCacheValue(value, true))
        scripts.put(cacheName, evictionsChannel, keySerializer, key, expiration.toMillis(), value)
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
            nearCache.put(key, RedisCacheValue(value, true))
            cache.add(Triple(key, expiration.toMillis(), value ?: ""))
        }
        scripts.putBatch(cacheName, evictionsChannel, keySerializer, cache)
    }

    override suspend fun putIfAbsent(key: CacheKey<K>, value: String): Boolean {
        val stored = scripts.putIfAbsent(
            cacheName,
            evictionsChannel,
            keySerializer,
            key,
            value,
            expiration.toMillis(),
        )
        if (stored) nearCache.invalidate(key)
        return stored
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? {
        if (keyPrefix) {
            val ids = scripts.removePrefix(cacheName, evictionsChannel, keySerializer, key)
            nearCache.invalidate(key)
            nearCache.invalidateAll(ids.map { it })
            return null
        } else {
            val removed = scripts.remove(cacheName, evictionsChannel, keySerializer, key)?.let { RedisCacheValue(it, true) }
            nearCache.invalidate(key)
            return removed
        }
    }

    override suspend fun clear() {
        nearCache.invalidateAll()
        scripts.clear(
            cacheName,
            evictionsAllChannel,
            LongKeySerializer.toRemoteKey(LongCacheKey("*", 0)),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun evictExpiredItems() {
        scripts.expiration(cacheName, evictionsAllChannel).forEach {
            val key = keySerializer.fromRemoteKey(it)
            nearCache.invalidate(key)
        }
    }

    override fun close() {
        lifecycleJobs.forEach { it.cancel() }
    }

    companion object {

        private val log = LoggerFactory.getLogger(NearCacheRedisCache::class.java)
    }
}
