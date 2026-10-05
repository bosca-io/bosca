package bosca.cache

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.ThreadLocalRandom

/**
 * Throughput with 64 concurrent callers, each acting as an independent request with its own [RequestCache].
 * With Redis this contends for the production default pool of 50 connections; with NATS it shares one
 * client connection.
 */
@State(Scope.Benchmark)
open class CacheBackendLoadBenchmark {

    private fun randomIndex(bound: Int): Int = ThreadLocalRandom.current().nextInt(bound)

    @Benchmark
    @Threads(64)
    open fun rawGetAcross64Callers(fixture: CacheBackendFixture): CacheValue = runBlocking {
        fixture.cache.get(fixture.hitLocalKeys[randomIndex(CacheBackendFixture.KEY_COUNT)])
    }

    @Benchmark
    @Threads(64)
    open fun serviceGetAcross64Callers(fixture: CacheBackendFixture): List<BenchmarkPermission>? =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.get(fixture.hitKeys[randomIndex(CacheBackendFixture.KEY_COUNT)])
        }

    @Benchmark
    @Threads(64)
    open fun serviceGetAll50Across64Callers(fixture: CacheBackendFixture): List<List<BenchmarkPermission>?> =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.getAll(fixture.hitBatches[randomIndex(fixture.hitBatches.size)])
        }

    @Benchmark
    @Threads(64)
    open fun serviceGetMissAndWriteAcross64Callers(fixture: CacheBackendFixture, miss: CacheMissKeys): List<BenchmarkPermission>? =
        runBlocking(fixture.newRequestCache().asCoroutineContext()) {
            fixture.serviceCache.get(miss.next())
        }
}
