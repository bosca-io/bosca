package bosca.cache

import bosca.db.ConnectionManagerCallback
import bosca.db.connectionOrNull
import bosca.di.provide
import bosca.graphql.Batch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext


class RequestCache(
    private val cacheManager: CacheManager,
    private val serializer: RequestCacheSerializer
) {

    private class LocalCacheRecord(
        val key: CacheKey<*>,
        val value: Any?,
        val prefix: Boolean = false
    ) {

        // Computed on first use by a prefix comparison; a racing duplicate computation yields an equal string.
        private var remotePrefix: String? = null

        fun remotePrefix(): String = remotePrefix ?: key.toRemoteKeyPrefix().also { remotePrefix = it }

        override fun toString(): String {
            return "LocalCacheRecord(key=$key, value=$value, prefix=$prefix)"
        }
    }

    // Grouped by cache name so a prefix removal scans only the entries of the cache it targets.
    private val local = ConcurrentHashMap<String, ConcurrentHashMap<CacheKey<*>, LocalCacheRecord>>()
    private val pendingRemotePut = ConcurrentHashMap<CacheKey<*>, LocalCacheRecord>()
    private val pendingRemoteRemove = ConcurrentHashMap<CacheKey<*>, LocalCacheRecord>()
    private var flushingAfterCommit = false
    private val mutex = Mutex()
    private val writeMutex = Mutex()

    private fun localEntries(cacheName: String): ConcurrentHashMap<CacheKey<*>, LocalCacheRecord> =
        local[cacheName] ?: local.computeIfAbsent(cacheName) { ConcurrentHashMap() }

    suspend fun <K, V> put(cacheName: String, key: K, value: V) {
        val keySerializer = cacheManager.getCache<K>(cacheName).keySerializer
        val localKey = keySerializer.toLocalKey(cacheName, key)
        localEntries(cacheName)[localKey] = LocalCacheRecord(localKey, value)
        mutex.withLock {
            pendingRemotePut[localKey] = LocalCacheRecord(localKey, value)
            pendingRemoteRemove.remove(localKey)
        }
        flushAfterCommit()
    }

    private fun hasPendingRemove(localKey: CacheKey<*>): Boolean {
        if (pendingRemoteRemove.isEmpty()) return false
        if (pendingRemoteRemove.containsKey(localKey)) return true
        var keyPrefix: String? = null
        for (record in pendingRemoteRemove.values) {
            if (!record.prefix || record.key.cacheName != localKey.cacheName) continue
            val prefix = keyPrefix ?: localKey.toRemoteKeyPrefix().also { keyPrefix = it }
            if (prefix.startsWith(record.remotePrefix())) return true
        }
        return false
    }

    suspend fun <K, V> get(cacheName: String, key: K, lookup: suspend () -> V?): V? {
        val cache = cacheManager.getCache<K>(cacheName)
        val localKey = cache.keySerializer.toLocalKey(cacheName, key)
        val local = localEntries(cacheName)
        val localResult = local[localKey]
        @Suppress("UNCHECKED_CAST")
        var result = localResult?.value as? V?
        if (localResult == null && !hasPendingRemove(localKey)) {
            val fromCache = cache.get(localKey)
            result = fromCache.value?.takeIf { it.isNotEmpty() }?.let { value ->
                @Suppress("UNCHECKED_CAST")
                serializer.deserialize(value) as V?
            }
            if (result == null && !fromCache.exists) {
                result = lookup()
                mutex.withLock {
                    pendingRemotePut[localKey] = LocalCacheRecord(localKey, result)
                }
                flushAfterCommit()
            }
            local[localKey] = LocalCacheRecord(localKey, result)
        } else if (localResult == null) {
            result = lookup()
            local[localKey] = LocalCacheRecord(localKey, result)
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <K, BV> getBatch(
        cacheName: String,
        batch: Batch<K, BV>,
        batchResolver: suspend (keys: List<K>, batch: Batch<K, BV>) -> Unit
    ) {
        val remoteGet = mutableListOf<CacheKey<K>>()
        val needsResolved = mutableListOf<K>()
        // Only keys the remote tier missed are written back. Keys resolved because a removal is pending are not,
        // matching get(): the flush applies that removal after its puts, so the write-back would be deleted at once.
        val writeBack = HashSet<K>()
        val cache = cacheManager.getCache<K>(cacheName)
        val keySerializer = cache.keySerializer
        val local = localEntries(cacheName)

        for (key in batch.keys) {
            val localKey = keySerializer.toLocalKey(cacheName, key)
            val localResult = local[localKey]
            if (localResult == null && !hasPendingRemove(localKey)) {
                remoteGet.add(localKey)
            } else if (localResult == null) {
                needsResolved.add(key)
            } else if (localResult.value != null) {
                batch.setData(key, localResult.value as BV)
            }
        }

        if (remoteGet.isNotEmpty()) {
            val remoteBatch = cache.getBatch(remoteGet)
            for ((index, batchItem) in remoteBatch.withIndex()) {
                val key = remoteGet[index]
                if (!batchItem.exists) {
                    needsResolved.add(key.key)
                    writeBack.add(key.key)
                    continue
                }
                val result = batchItem.value?.takeIf { it.isNotEmpty() }?.let { serializer.deserialize(it) as BV? }
                result?.let { batch.setData(key.key, it) }
                local[key] = LocalCacheRecord(key, result)
            }
        }

        if (needsResolved.isNotEmpty()) {
            batchResolver(needsResolved, batch)
            val resolved = needsResolved.map { key ->
                val localKey = keySerializer.toLocalKey(cacheName, key)
                LocalCacheRecord(localKey, batch.getData(key)).also { local[localKey] = it }
            }
            if (writeBack.isNotEmpty()) {
                mutex.withLock {
                    resolved.forEachIndexed { index, record ->
                        if (needsResolved[index] in writeBack) pendingRemotePut[record.key] = record
                    }
                }
            }
        }

        if (writeBack.isNotEmpty()) {
            flushAfterCommit()
        }
    }

    suspend fun <K> remove(cacheName: String, key: K, keyPrefix: Boolean) {
        val keySerializer = cacheManager.getCache<K>(cacheName).keySerializer
        val localKey = keySerializer.toLocalKey(cacheName, key)
        if (keyPrefix) {
            val removal = LocalCacheRecord(localKey, null, true)
            val prefix = removal.remotePrefix()
            val local = localEntries(cacheName)
            val matching = local.values.filter { it.remotePrefix().startsWith(prefix) }.map { it.key }
            matching.forEach { local.remove(it) }
            mutex.withLock {
                matching.forEach { pendingRemotePut.remove(it) }
                pendingRemoteRemove[localKey] = removal
            }
        } else {
            localEntries(cacheName).remove(localKey)
            mutex.withLock {
                pendingRemotePut.remove(localKey)
                pendingRemoteRemove[localKey] = LocalCacheRecord(localKey, null)
            }
        }
        flushAfterCommit()
    }

    fun clearLocal() {
        local.clear()
        pendingRemoteRemove.clear()
        pendingRemotePut.clear()
    }

    suspend fun clear(cacheName: String) {
        clearLocal()
        cacheManager.getCache<Any>(cacheName).clear()
    }


    suspend fun flush() {
        mutex.withLock {
            flushInternal()
        }
    }

    private suspend fun flushInternal() {
        if (pendingRemotePut.isEmpty() && pendingRemoteRemove.isEmpty()) {
            flushingAfterCommit = false
            return
        }
        val pendingPut = pendingRemotePut.values.groupBy { it.key.cacheName }
        val pendingRemove = pendingRemoteRemove.values.toList()
        pendingRemotePut.clear()
        pendingRemoteRemove.clear()
        flushingAfterCommit = false
        // Caches are independent, so each phase runs every cache concurrently: all writes, then all removals. Within a
        // cache, writes still land before removals, so a removal covering a written key still deletes it.
        writeMutex.withLock {
            coroutineScope {
                pendingPut.map { (cacheName, records) ->
                    async {
                        @Suppress("UNCHECKED_CAST")
                        val pending = records.map { record ->
                            record.key as CacheKey<Any> to (record.value?.let { serializer.serialize(record.value) })
                        }
                        cacheManager.getCache<Any>(cacheName).putBatch(pending)
                    }
                }.awaitAll()
            }
            coroutineScope {
                pendingRemove.groupBy { it.key.cacheName }.map { (cacheName, records) ->
                    async {
                        val (prefixes, keys) = records.partition { it.prefix }
                        @Suppress("UNCHECKED_CAST")
                        cacheManager.getCache<Any>(cacheName).removeBatch(
                            keys.map { it.key as CacheKey<Any> },
                            prefixes.map { it.key as CacheKey<Any> },
                        )
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun flushAfterCommit() {
        if (pendingRemotePut.isEmpty() && pendingRemoteRemove.isEmpty()) return
        mutex.withLock {
            if (flushingAfterCommit) {
                return@withLock
            }
            flushingAfterCommit = true
            connectionOrNull()?.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() {
                    flush()
                }

                override suspend fun onRelease() {
                    flush()
                }
            }) ?: flushInternal()
        }
    }
}

fun RequestCache.asCoroutineContext(): CoroutineContext = RequestCacheContext(this)

private class RequestCacheContext(val cache: RequestCache) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RequestCacheContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend fun requestCache(): RequestCache = currentCoroutineContext().requestCache()

fun CoroutineContext.requestCache(): RequestCache = this[RequestCacheContext.Key]?.cache ?: throw IllegalStateException("Request cache not found in coroutine context")

suspend fun <T> withRequestCache(block: suspend () -> T): T {
    val cache = RequestCache(provide(), provide())
    return withContext(cache.asCoroutineContext()) {
        block()
    }
}