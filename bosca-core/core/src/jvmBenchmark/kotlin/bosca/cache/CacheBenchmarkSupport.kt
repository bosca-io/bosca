package bosca.cache

import bosca.security.model.PermissionAction
import bosca.serialization.SerializerCache
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Duration

/**
 * Mirrors the shape of the platform's most common cached value: `ServiceCache<UUID, List<EntityPermission>>`
 * backs eleven caches (collection, metadata, script, dashboard, ... permissions).
 */
@Serializable
data class BenchmarkPermission(
    val entityId: UUID,
    val groupId: UUID,
    val action: PermissionAction,
)

/** A mid-sized domain record (~1 KiB of JSON) comparable to a cached Company, Product, or FormSchema. */
@Serializable
data class BenchmarkDocument(
    val id: UUID,
    val name: String,
    val description: String,
    val labels: List<String>,
    val attributes: Map<String, String>,
    val created: String,
    val modified: String,
    val version: Long,
    val deleted: Boolean = false,
)

/** The value kinds exercised by the serializer and request-cache benchmarks. */
object CacheBenchmarkPayloads {

    const val PERMISSION_LIST = "permissionList"
    const val DOCUMENT = "document"

    /** Matches the production `BoscaApplication.json` flags; the contextual module is irrelevant to these types. */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
    }

    fun permission(seed: Int = 0): BenchmarkPermission = BenchmarkPermission(
        entityId = UUID.fromLongs(seed.toLong(), 1),
        groupId = UUID.fromLongs(seed.toLong(), 2),
        action = PermissionAction.entries[seed % PermissionAction.entries.size],
    )

    /** Five grants per entity approximates a collection with a handful of group bindings. */
    fun permissionList(seed: Int = 0): List<BenchmarkPermission> = List(5) { permission(seed * 5 + it) }

    fun document(seed: Int = 0): BenchmarkDocument = BenchmarkDocument(
        id = UUID.fromLongs(seed.toLong(), 3),
        name = "Benchmark document $seed",
        description = "A representative description for a cached domain record. ".repeat(6),
        labels = List(8) { "label-$it" },
        attributes = (0 until 12).associate { "attribute-$it" to "value-$it-$seed" },
        created = "2026-09-24T12:00:00Z",
        modified = "2026-09-24T12:30:00Z",
        version = seed.toLong(),
    )

    fun value(kind: String, seed: Int = 0): Any = when (kind) {
        PERMISSION_LIST -> permissionList(seed)
        DOCUMENT -> document(seed)
        else -> error("Unknown payload kind: $kind")
    }

    /**
     * Pre-registers the benchmark types in [SerializerCache], mimicking a process where the KSP-generated
     * `SerializerRegistrar` ran. Without this, [RequestCacheSerializerImpl] falls back to reflective lookup.
     */
    fun registerSerializers() {
        SerializerCache.register(BenchmarkPermission::class.java, BenchmarkPermission.serializer())
        SerializerCache.register(BenchmarkDocument::class.java, BenchmarkDocument.serializer())
    }
}

/**
 * A process-local [Cache] with no network, used to isolate [RequestCache] and serializer overhead from
 * backend latency. Semantics follow the remote caches: `put(key, null)` stores a negative entry. Entries are kept
 * sorted so a prefix removal touches only the matching range, keeping backend cost out of invalidation benchmarks.
 */
class InMemoryCache<K>(override val keySerializer: CacheKeySerializer<K>) : Cache<K> {

    private class Value(override val value: String?, override val exists: Boolean) : CacheValue

    private val missing = Value(null, false)
    private val entries = ConcurrentSkipListMap<String, Value>()

    override suspend fun get(key: CacheKey<K>): CacheValue = entries[keySerializer.toRemoteKey(key)] ?: missing

    override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> = keys.map { get(it) }

    override suspend fun put(key: CacheKey<K>, value: String?) {
        entries[keySerializer.toRemoteKey(key)] = Value(value, true)
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) {
        entries.forEach { (key, value) -> put(key, value) }
    }

    override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? {
        if (keyPrefix) {
            val prefix = keySerializer.toRemoteKeyPrefix(key)
            entries.subMap(prefix, true, prefix + Char.MAX_VALUE, true).clear()
            return null
        }
        return entries.remove(keySerializer.toRemoteKey(key))
    }

    override suspend fun clear() = entries.clear()

    override suspend fun evictExpiredItems() = Unit

    override val estimatedSize: Long get() = entries.size.toLong()
}

/** A [CacheManager] over [InMemoryCache] instances. */
class InMemoryCacheManager : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache<*>>()

    override val cacheNames: Set<String> get() = caches.keys

    @Suppress("UNCHECKED_CAST")
    override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> =
        caches.computeIfAbsent(name) { InMemoryCache(keySerializer) } as Cache<K>

    @Suppress("UNCHECKED_CAST")
    override suspend fun <K> getCache(name: String): Cache<K> = (caches[name] ?: error("Cache $name not found")) as Cache<K>

    override suspend fun evictExpiredItems() = Unit

    override suspend fun clearAll() = caches.values.forEach { it.clear() }
}
