package bosca.cache

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Single-caller latency of each cache operation against a real Redis (Valkey) or NATS KeyValue backend, selected by
 * the `backend` parameter of [CacheBackendFixture].
 *
 * `raw*` benchmarks call [Cache] directly and are the backend floor. `service*` benchmarks go through
 * [ServiceCache] with a fresh [RequestCache] per operation, as each GraphQL request does, so
 * `service*` minus the matching `raw*` is Bosca's own overhead on that operation.
 */
@State(Scope.Thread)
open class CacheBackendBenchmark {

    private var cursor = 0

    private fun nextIndex(): Int {
        val index = cursor
        cursor = (index + 1) % CacheBackendFixture.KEY_COUNT
        return index
    }

    private fun nextBatchIndex(fixture: CacheBackendFixture): Int = nextIndex() % fixture.hitBatches.size

    @Benchmark
    open fun rawGet(fixture: CacheBackendFixture): CacheValue = runBlocking {
        fixture.cache.get(fixture.hitLocalKeys[nextIndex()])
    }

    @Benchmark
    open fun rawGetBatch50(fixture: CacheBackendFixture): List<CacheValue> = runBlocking {
        fixture.cache.getBatch(fixture.hitLocalBatches[nextBatchIndex(fixture)])
    }

    @Benchmark
    open fun rawPutBatch50(fixture: CacheBackendFixture): Int = runBlocking {
        val batch = nextBatchIndex(fixture)
        fixture.cache.putBatch(fixture.hitLocalBatches[batch].map { it to fixture.encodedValue })
        batch
    }

    @Benchmark
    open fun serviceGet(fixture: CacheBackendFixture): List<BenchmarkPermission>? =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.get(fixture.hitKeys[nextIndex()])
        }

    @Benchmark
    open fun serviceGetAll50(fixture: CacheBackendFixture): List<List<BenchmarkPermission>?> =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.getAll(fixture.hitBatches[nextBatchIndex(fixture)])
        }

    /** An absent key: remote miss, resolver, then the write-back flush. */
    @Benchmark
    open fun serviceGetMissAndWrite(fixture: CacheBackendFixture, miss: CacheMissKeys): List<BenchmarkPermission>? =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.get(miss.next())
        }

    /** Fifty absent keys: one batched remote miss, the batch resolver, then the write-back flush. */
    @Benchmark
    open fun serviceGetAll50MissAndWrite(fixture: CacheBackendFixture, miss: CacheMissKeys): List<List<BenchmarkPermission>?> =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.getAll(miss.nextBatch())
        }
}
