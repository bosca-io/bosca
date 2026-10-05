package bosca.cache

import bosca.serialization.UUID
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.runBlocking

/**
 * [BenchmarkEntityService] over a real Redis (Valkey) or NATS KeyValue backend. Each operation is one
 * production-shaped request, and its deferred write-backs and removals flush on `release()` inside the measured time,
 * as they do on the request path. The backend starts with [ServiceBenchmarkFixture.ENTITY_COUNT] entities in each of
 * the service's nine caches, so a prefix removal scans a realistically sized hash or bucket.
 */
@State(Scope.Benchmark)
open class ServiceBackendFixture {

    @Param(BenchmarkCacheBackend.REDIS, BenchmarkCacheBackend.NATS)
    lateinit var backend: String

    val ids: List<UUID> = List(ServiceBenchmarkFixture.ENTITY_COUNT) { UUID.fromLongs(it.toLong(), 9) }
    val listings: List<List<UUID>> = ids.chunked(ServiceBenchmarkFixture.LISTING_SIZE)

    private lateinit var connection: BenchmarkCacheBackend
    lateinit var service: BenchmarkEntityService
        private set
    lateinit var harness: ServiceRequestHarness
        private set

    @Setup
    open fun setup() {
        connection = BenchmarkCacheBackend(backend)
        val serializer: RequestCacheSerializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
            .also { CacheBenchmarkPayloads.registerSerializers() }
        service = BenchmarkEntityService(connection.manager).also { runBlocking { it.preload(ids, serializer) } }
        harness = ServiceRequestHarness(connection.manager, serializer)
    }

    @TearDown
    open fun tearDown() {
        harness.close()
        connection.close()
    }
}

/** Read-only requests: every value is a remote hit, so these count the read round trips. */
@State(Scope.Thread)
open class ServiceBackendRequestBenchmark {

    private var cursor = 0

    @Benchmark
    open fun readEntity(fixture: ServiceBackendFixture): Int =
        fixture.harness.request { fixture.service.readEntity(fixture.ids[cursor++ % fixture.ids.size]) }

    @Benchmark
    open fun readListing50(fixture: ServiceBackendFixture): Int =
        fixture.harness.request { fixture.service.readListing(fixture.listings[cursor++ % fixture.listings.size]) }

    /** The same five batches issued concurrently: the ceiling for dispatching DataLoaders concurrently. */
    @Benchmark
    open fun readListing50Concurrently(fixture: ServiceBackendFixture): Int =
        fixture.harness.request { fixture.service.readListingConcurrently(fixture.listings[cursor++ % fixture.listings.size]) }

    /** Eight concurrent sibling resolvers reading the same entity and permissions, as query fields resolve. */
    @Benchmark
    open fun readSiblingFields8(fixture: ServiceBackendFixture): Int =
        fixture.harness.request { fixture.service.readSiblingFields(fixture.ids[cursor++ % fixture.ids.size], 8) }

    /** One sibling resolver: the no-duplication floor for [readSiblingFields8]. */
    @Benchmark
    open fun readSiblingFields1(fixture: ServiceBackendFixture): Int =
        fixture.harness.request { fixture.service.readSiblingFields(fixture.ids[cursor++ % fixture.ids.size], 1) }
}

/**
 * A mutation request: read a 50-entity listing, invalidate `mutations` of those entities as
 * `MetadataServiceImpl.removeFromCache` does (one exact plus eight prefix removals each), read the listing again, and
 * flush on release. Invalidated entities miss on their listing's next turn and are written back then, so every
 * operation also flushes that many write-backs.
 */
@State(Scope.Thread)
open class ServiceBackendMutationBenchmark {

    @Param("1", "50")
    lateinit var mutations: String

    private var cursor = 0

    @Benchmark
    open fun invalidateThenReadListing50(fixture: ServiceBackendFixture): Int = fixture.harness.request {
        val listing = fixture.listings[cursor++ % fixture.listings.size]
        val first = fixture.service.readListing(listing)
        listing.take(mutations.toInt()).forEach { fixture.service.removeFromCache(it) }
        first + fixture.service.readListing(listing)
    }
}
