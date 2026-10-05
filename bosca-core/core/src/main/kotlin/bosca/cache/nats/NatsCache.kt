package bosca.cache.nats

import bosca.cache.Cache
import bosca.cache.CacheDispatcher
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheValue
import bosca.nats.NatsConnectionPool
import bosca.observability.ErrorCapture
import io.nats.client.KeyValue
import io.nats.client.JetStreamApiException
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val NATS_HEX_DIGITS = "0123456789abcdef"

internal fun String.sanitizeForNats(): String {
    var sanitized: StringBuilder? = null
    for ((index, character) in withIndex()) {
        if (
            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '-' || character == '/' || character == '='
        ) {
            sanitized?.append(character)
        } else {
            // A fixed-width escape keeps distinct keys distinct and preserves prefix matching.
            val builder = sanitized ?: StringBuilder(length).append(this, 0, index).also { sanitized = it }
            val code = character.code
            builder.append('_')
            builder.append(NATS_HEX_DIGITS[code ushr 12])
            builder.append(NATS_HEX_DIGITS[(code ushr 8) and 0xf])
            builder.append(NATS_HEX_DIGITS[(code ushr 4) and 0xf])
            builder.append(NATS_HEX_DIGITS[code and 0xf])
        }
    }
    return sanitized?.toString() ?: this
}

class NatsCache<K>(
    private val kv: KeyValue,
    override val keySerializer: CacheKeySerializer<K>,
    private val errorCapture: ErrorCapture = ErrorCapture.Noop,
) : Cache<K> {

    private class NatsCacheValue(
        override val value: String?,
        override val exists: Boolean
    ) : CacheValue

    private fun CacheKey<K>.toKey(): String {
        return keySerializer.toRemoteKey(this).sanitizeForNats()
    }

    private fun CacheKey<K>.toKeyPrefix(): String {
        return keySerializer.toRemoteKeyPrefix(this).sanitizeForNats()
    }

    override suspend fun get(key: CacheKey<K>): CacheValue = withContext(CacheDispatcher) {
        getCurrent(key)
    }

    private suspend fun getEntry(k: String, location: String): KeyValueEntry? = try {
        kv.get(k)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("NATS cache read failed for key '{}': {}", k, e.message, e)
        errorCapture.capture(e, null, mapOf("location" to location, "key" to k))
        throw e
    }

    private suspend fun getCurrent(key: CacheKey<K>): CacheValue {
        val k = key.toKey()
        val entry = getEntry(k, "NatsCache.get")
        return if (entry == null || entry.operation == KeyValueOperation.DELETE) {
            NatsCacheValue(null, false)
        } else {
            NatsCacheValue(entry.valueAsString, true)
        }
    }

    override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> = withContext(CacheDispatcher) {
        concurrentReads(keys, ::getCurrent)
    }

    override suspend fun getAndTouch(key: CacheKey<K>): CacheValue = withContext(CacheDispatcher) {
        getAndTouchCurrent(key)
    }

    override suspend fun getBatchAndTouch(keys: List<CacheKey<K>>): List<CacheValue> = withContext(CacheDispatcher) {
        concurrentReads(keys, ::getAndTouchCurrent)
    }

    private suspend fun concurrentReads(
        keys: List<CacheKey<K>>,
        read: suspend (CacheKey<K>) -> CacheValue,
    ): List<CacheValue> {
        val uniqueKeys = linkedMapOf<String, CacheKey<K>>()
        keys.forEach { key -> uniqueKeys.putIfAbsent(key.toKey(), key) }
        val values = coroutineScope {
            uniqueKeys.values.map { key -> async { read(key) } }.awaitAll()
        }
        val valuesByKey = uniqueKeys.keys.zip(values).toMap()
        return keys.map { key -> valuesByKey.getValue(key.toKey()) }
    }

    private suspend fun getAndTouchCurrent(key: CacheKey<K>): CacheValue {
        val k = key.toKey()
        var entry = getEntry(k, "NatsCache.getAndTouch")
        repeat(TOUCH_ATTEMPTS) {
            if (entry == null || entry.operation == KeyValueOperation.DELETE) return NatsCacheValue(null, false)
            try {
                kv.update(k, entry.value, entry.revision)
                return NatsCacheValue(entry.valueAsString, true)
            } catch (e: JetStreamApiException) {
                if (e.apiErrorCode != REVISION_CONFLICT_ERROR) throw e
            }
            val previous = entry
            entry = getEntry(k, "NatsCache.getAndTouch")
            if (entry != null && entry.operation != KeyValueOperation.DELETE && entry.value.contentEquals(previous.value)) {
                return NatsCacheValue(entry.valueAsString, true)
            }
        }
        error("NATS cache get-and-touch remained contended for key '$k'")
    }

    override suspend fun put(key: CacheKey<K>, value: String?) = withContext(CacheDispatcher) {
        putCurrent(key.toKey(), value)
    }

    private fun putCurrent(k: String, value: String?) {
        if (value == null) {
            kv.delete(k)
            kv.purge(k)
        } else {
            kv.put(k, value)
        }
    }

    /**
     * Writes every entry concurrently, one virtual thread per key, so the batch costs about one NATS round trip
     * instead of one per entry. Entries that map to the same NATS key collapse to the last one, which keeps the
     * last-write-wins result of applying the list in order.
     */
    override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) = withContext(CacheDispatcher) {
        val latest = linkedMapOf<String, String?>()
        entries.forEach { (key, value) -> latest[key.toKey()] = value }
        coroutineScope {
            latest.map { (k, value) -> async { putCurrent(k, value) } }.awaitAll()
        }
        Unit
    }

    override suspend fun putIfAbsent(key: CacheKey<K>, value: String): Boolean = withContext(CacheDispatcher) {
        val k = key.toKey()
        try {
            kv.create(k, value.toByteArray())
            true
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode == REVISION_CONFLICT_ERROR) false else throw e
        }
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? = withContext(CacheDispatcher) {
        if (keyPrefix) {
            val prefix = key.toKeyPrefix()
            val keys = kv.keys()
            keys.filter { it.startsWith(prefix) }.forEach {
                kv.delete(it)
                kv.purge(it)
            }
            null
        } else {
            removeCurrent(key.toKey())
        }
    }

    /**
     * Lists the bucket's keys once for all [prefixes], instead of once per prefix, and runs every deletion
     * concurrently. Exact keys use the same revision-checked removal as [remove].
     */
    override suspend fun removeBatch(keys: List<CacheKey<K>>, prefixes: List<CacheKey<K>>) = withContext(CacheDispatcher) {
        val prefixMatches = if (prefixes.isEmpty()) {
            emptyList()
        } else {
            val wanted = prefixes.map { it.toKeyPrefix() }
            kv.keys().filter { stored -> wanted.any { stored.startsWith(it) } }
        }
        coroutineScope {
            val exact = keys.map { it.toKey() }.distinct().map { k -> async { removeCurrent(k) } }
            val prefixed = prefixMatches.map { k ->
                async {
                    kv.delete(k)
                    kv.purge(k)
                }
            }
            (exact + prefixed).awaitAll()
        }
        Unit
    }

    private fun removeCurrent(remoteKey: String): CacheValue? {
        repeat(REMOVE_ATTEMPTS) {
            val current = kv.get(remoteKey)
            if (current == null || current.operation == KeyValueOperation.DELETE) return null
            try {
                kv.delete(remoteKey, current.revision)
                return NatsCacheValue(current.valueAsString, true)
            } catch (e: JetStreamApiException) {
                if (e.apiErrorCode != REVISION_CONFLICT_ERROR) throw e
            }
        }
        error("NATS cache remove remained contended for key '$remoteKey'")
    }

    override suspend fun clear() = withContext(CacheDispatcher) {
        kv.keys().forEach {
            kv.delete(it)
            kv.purge(it)
        }
    }

    override suspend fun evictExpiredItems() {
    }

    override val estimatedSize: Long
        get() = try {
            kv.status.entryCount
        } catch (e: Exception) {
            log.warn("NATS cache estimatedSize failed: {}", e.message, e)
            -1L
        }

    companion object {

        private const val REVISION_CONFLICT_ERROR = 10071
        private const val REMOVE_ATTEMPTS = 4
        private const val TOUCH_ATTEMPTS = 4

        private val log = LoggerFactory.getLogger(NatsCache::class.java)

        suspend fun <K> newCache(pool: NatsConnectionPool, cacheName: String, expiration: Duration, cacheKeySerializer: CacheKeySerializer<K>) = withContext(CacheDispatcher) {
            val connection = pool.systemConnection()
            val bucketName = cacheName.replace(':', '-')
            NatsCache(
                try {
                    connection.keyValue(bucketName)
                } catch (e: Exception) {
                    val management = connection.keyValueManagement()
                    management.create(
                        KeyValueConfiguration.builder()
                            .name(bucketName)
                            .ttl(expiration.toJavaDuration())
                            .build()
                    )
                    connection.keyValue(bucketName)
                }, cacheKeySerializer
            )
        }
    }
}
