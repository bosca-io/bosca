package bosca.cache

import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.service.ServiceCacheImpl
import bosca.graphql.Batch
import bosca.serialization.UUID
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Level
import java.util.concurrent.atomic.AtomicLong

/**
 * Starts a real cache backend from the shared test-resource services and wires the production [CacheManager] for
 * it, so benchmarks drive the same Lua scripts (Redis) or KeyValue calls (NATS) that the server does.
 *
 * `redis` uses the Valkey service with the production default pool of 50 connections. `nats` uses a JetStream
 * KeyValue bucket; [NatsCache] issues one request per key, so its batch reads and writes fan out concurrently.
 */
@State(Scope.Benchmark)
open class CacheBackendFixture {

    @Param(REDIS, NATS)
    lateinit var backend: String

    private lateinit var backendConnection: BenchmarkCacheBackend

    val manager: CacheManager get() = backendConnection.manager
    lateinit var cache: Cache<UUID>
        private set
    lateinit var serviceCache: ServiceCacheImpl<UUID, List<BenchmarkPermission>>
        private set

    /** Serializer types are pre-registered so backend comparisons are not dominated by reflective lookup. */
    val serializer: RequestCacheSerializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
        .also { CacheBenchmarkPayloads.registerSerializers() }

    val hitKeys: List<UUID> = List(KEY_COUNT) { UUID.fromLongs(it.toLong(), HIT_NAMESPACE) }
    val hitLocalKeys: List<CacheKey<UUID>> = hitKeys.map { it.toLocalKey() }
    val hitBatches: List<List<UUID>> = List(KEY_COUNT / BATCH_SIZE) { hitKeys.subList(it * BATCH_SIZE, (it + 1) * BATCH_SIZE) }
    val hitLocalBatches: List<List<CacheKey<UUID>>> = hitBatches.map { batch -> batch.map { it.toLocalKey() } }
    val encodedValue: String = checkNotNull(serializer.serialize(CacheBenchmarkPayloads.permissionList()))

    private val resolvedValue = CacheBenchmarkPayloads.permissionList()

    @Setup
    open fun start() = runBlocking {
        backendConnection = BenchmarkCacheBackend(backend)
        cache = manager.maybeAddCache(CACHE_NAME, UUIDKeySerializer)
        cache.clear()
        cache.putBatch(hitLocalKeys.mapIndexed { index, key ->
            key to serializer.serialize(CacheBenchmarkPayloads.permissionList(index))
        })
        check(cache.getBatch(hitLocalKeys).all { it.exists }) { "$backend did not retain the preloaded entries" }
        serviceCache = ServiceCacheImpl(
            CACHE_NAME,
            batchResolver = { keys: List<UUID>, batch: Batch<UUID, List<BenchmarkPermission>> ->
                keys.forEach { batch.setData(it, resolvedValue) }
            },
        ) { resolvedValue }
    }

    @TearDown
    open fun stop() = backendConnection.close()

    fun newRequestCache(): RequestCache = RequestCache(manager, serializer)

    /** Deletes [keys] from the backend concurrently, so the next read of each misses again. */
    suspend fun evict(keys: List<UUID>) = coroutineScope {
        keys.map { key -> async { cache.remove(key.toLocalKey()) } }.awaitAll()
    }

    private fun UUID.toLocalKey(): CacheKey<UUID> = UUIDKeySerializer.toLocalKey(CACHE_NAME, this)

    companion object {
        const val REDIS = BenchmarkCacheBackend.REDIS
        const val NATS = BenchmarkCacheBackend.NATS
        const val CACHE_NAME = "benchmark:permissions"
        const val KEY_COUNT = 1_000
        const val BATCH_SIZE = 50
        private const val HIT_NAMESPACE = 1L
    }
}

/**
 * Keys that are absent when a `*MissAndWrite` operation starts. The keys rotate through a bounded per-thread pool
 * and every invocation's write-back is evicted after it completes, outside the measured time, so backend storage
 * stays bounded. This matters for NATS, whose shared test accounts have a 32 MB JetStream quota.
 */
@State(Scope.Thread)
open class CacheMissKeys {

    private val namespace = MISS_KEY_BASE + namespaces.incrementAndGet()
    private var cursor = 0L
    private var pending: List<UUID> = emptyList()

    fun next(): UUID = take(1).single()

    fun nextBatch(): List<UUID> = take(CacheBackendFixture.BATCH_SIZE)

    private fun take(count: Int): List<UUID> =
        List(count) { UUID.fromLongs(namespace, cursor++ % POOL_SIZE) }.also { pending = it }

    @TearDown(Level.Invocation)
    open fun evict(fixture: CacheBackendFixture) {
        if (pending.isEmpty()) return
        runBlocking { fixture.evict(pending) }
        pending = emptyList()
    }

    private companion object {
        const val POOL_SIZE = 1_000L
        const val MISS_KEY_BASE = 1_000_000_000L
        val namespaces = AtomicLong()
    }
}
