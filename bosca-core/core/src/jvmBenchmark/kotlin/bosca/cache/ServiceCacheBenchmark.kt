package bosca.cache

import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.service.ServiceCacheImpl
import bosca.graphql.Batch
import bosca.serialization.UUID
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Measures the [ServiceCache] → [RequestCache] → serializer path against an in-process [InMemoryCache], so the
 * results contain no network time. Every figure here is overhead Bosca adds on top of the backend round trip
 * that [CacheBackendBenchmark] measures.
 *
 * Each operation runs inside `runBlocking` with a [RequestCache] context, as a GraphQL resolver does;
 * [runBlockingControl] is that fixed cost alone and should be subtracted when comparing. Payload types are
 * pre-registered in `SerializerCache`, as for the backend benchmarks.
 *
 * - `firstTouch*` builds a new RequestCache per operation, which is what every GraphQL request and route does.
 * - `repeat*` reuses one primed RequestCache, i.e. the second and later reads of a key in the same request.
 * - `miss*` resolves through the resolver and writes back; the written entry is dropped from the in-memory backend
 *   afterwards (a map removal) so the key misses again on its next turn.
 */
@State(Scope.Benchmark)
open class ServiceCacheBenchmark {

    private val manager = InMemoryCacheManager()
    private lateinit var serializer: RequestCacheSerializer
    private lateinit var backend: Cache<UUID>
    private lateinit var serviceCache: ServiceCacheImpl<UUID, List<BenchmarkPermission>>
    private lateinit var primedRequestCache: RequestCache

    private val hitKeys = List(KEY_COUNT) { UUID.fromLongs(it.toLong(), HIT_NAMESPACE) }
    private val missKeys = List(KEY_COUNT) { UUID.fromLongs(it.toLong(), MISS_NAMESPACE) }
    private val hitBatches = List(KEY_COUNT / BATCH_SIZE) { hitKeys.subList(it * BATCH_SIZE, (it + 1) * BATCH_SIZE) }
    private val missBatches = List(KEY_COUNT / BATCH_SIZE) { missKeys.subList(it * BATCH_SIZE, (it + 1) * BATCH_SIZE) }
    private val resolvedValue = CacheBenchmarkPayloads.permissionList()
    private var cursor = 0

    @Setup
    open fun setup() = runBlocking {
        CacheBenchmarkPayloads.registerSerializers()
        serializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
        backend = manager.maybeAddCache(CACHE_NAME, UUIDKeySerializer)
        hitKeys.forEachIndexed { index, key ->
            backend.put(UUIDKeySerializer.toLocalKey(CACHE_NAME, key), serializer.serialize(CacheBenchmarkPayloads.permissionList(index)))
        }
        serviceCache = ServiceCacheImpl(
            CACHE_NAME,
            batchResolver = { keys: List<UUID>, batch: Batch<UUID, List<BenchmarkPermission>> ->
                keys.forEach { batch.setData(it, resolvedValue) }
            },
        ) { resolvedValue }
        primedRequestCache = RequestCache(manager, serializer)
        runBlocking(primedRequestCache.asCoroutineContext()) {
            hitKeys.forEach { checkNotNull(serviceCache.get(it)) { "Primed key $it did not resolve" } }
        }
    }

    private fun nextIndex(): Int {
        val index = cursor
        cursor = (index + 1) % KEY_COUNT
        return index
    }

    private fun newRequestCache() = RequestCache(manager, serializer)

    @Benchmark
    open fun runBlockingControl(): Int = runBlocking(newRequestCache().asCoroutineContext()) { cursor }

    @Benchmark
    open fun repeatGet(): List<BenchmarkPermission>? = runBlocking(primedRequestCache.asCoroutineContext()) {
        serviceCache.get(hitKeys[nextIndex()])
    }

    @Benchmark
    open fun firstTouchGet(): List<BenchmarkPermission>? = runBlocking(newRequestCache().asCoroutineContext()) {
        serviceCache.get(hitKeys[nextIndex()])
    }

    @Benchmark
    open fun missGetAndWrite(): List<BenchmarkPermission>? = runBlocking(newRequestCache().asCoroutineContext()) {
        val key = missKeys[nextIndex()]
        serviceCache.get(key).also { backend.remove(UUIDKeySerializer.toLocalKey(CACHE_NAME, key)) }
    }

    @Benchmark
    open fun firstTouchGetAll50(): List<List<BenchmarkPermission>?> = runBlocking(newRequestCache().asCoroutineContext()) {
        serviceCache.getAll(hitBatches[nextIndex() % hitBatches.size])
    }

    @Benchmark
    open fun missGetAll50AndWrite(): List<List<BenchmarkPermission>?> = runBlocking(newRequestCache().asCoroutineContext()) {
        val keys = missBatches[nextIndex() % missBatches.size]
        serviceCache.getAll(keys).also {
            keys.forEach { key -> backend.remove(UUIDKeySerializer.toLocalKey(CACHE_NAME, key)) }
        }
    }

    private companion object {
        const val CACHE_NAME = "benchmark:permissions"
        const val KEY_COUNT = 1_000
        const val BATCH_SIZE = 50
        const val HIT_NAMESPACE = 1L
        const val MISS_NAMESPACE = 2L
    }
}
