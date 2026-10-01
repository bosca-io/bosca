package bosca.cache

import bosca.serialization.UUID
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.runBlocking

/**
 * Read-only requests against [BenchmarkEntityService], each a full production-shaped request (fresh RequestCache and
 * ConnectionManager, deferred flush on release) over a warm in-memory backend. [emptyRequest] is the request
 * lifecycle alone. [readListing50Twice] is the control for [ServiceMutationBenchmark].
 */
@State(Scope.Benchmark)
open class ServiceRequestBenchmark {

    private lateinit var fixture: ServiceBenchmarkFixture
    private var cursor = 0

    @Setup
    open fun setup() {
        fixture = ServiceBenchmarkFixture()
    }

    @TearDown
    open fun tearDown() = fixture.close()

    private fun nextId(): UUID = fixture.ids[cursor++ % fixture.ids.size]

    private fun nextListing(): List<UUID> = fixture.listings[cursor++ % fixture.listings.size]

    @Benchmark
    open fun emptyRequest(): Int = fixture.harness.request { 0 }

    @Benchmark
    open fun readEntity(): Int = fixture.harness.request { fixture.service.readEntity(nextId()) }

    @Benchmark
    open fun readListing50(): Int = fixture.harness.request { fixture.service.readListing(nextListing()) }

    @Benchmark
    open fun readListing50Twice(): Int = fixture.harness.request {
        val listing = nextListing()
        fixture.service.readListing(listing) + fixture.service.readListing(listing)
    }
}

/** Shared service, backend, and request harness for the service-shaped benchmarks. */
class ServiceBenchmarkFixture {

    val ids: List<UUID> = List(ENTITY_COUNT) { UUID.fromLongs(it.toLong(), 7) }
    val listings: List<List<UUID>> = ids.chunked(LISTING_SIZE)

    private val manager = InMemoryCacheManager()
    private val serializer: RequestCacheSerializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
        .also { CacheBenchmarkPayloads.registerSerializers() }

    val service = BenchmarkEntityService(manager).also { runBlocking { it.preload(ids, serializer) } }
    val harness = ServiceRequestHarness(manager, serializer)

    fun close() = harness.close()

    companion object {
        const val ENTITY_COUNT = 1_000
        const val LISTING_SIZE = 50
    }
}
