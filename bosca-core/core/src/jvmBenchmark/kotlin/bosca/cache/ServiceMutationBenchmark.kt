package bosca.cache

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * A mutation request against [BenchmarkEntityService]: read a 50-entity listing, invalidate `mutations` of those
 * entities the way `MetadataServiceImpl.removeFromCache` does (one exact plus eight prefix removals each), then read
 * the listing again, all inside one request with deferred flush. Compare with
 * [ServiceRequestBenchmark.readListing50Twice], which does the same reads without the invalidations.
 */
@State(Scope.Benchmark)
open class ServiceMutationBenchmark {

    @Param("1", "50")
    lateinit var mutations: String

    private lateinit var fixture: ServiceBenchmarkFixture
    private var cursor = 0

    @Setup
    open fun setup() {
        fixture = ServiceBenchmarkFixture()
    }

    @TearDown
    open fun tearDown() = fixture.close()

    @Benchmark
    open fun invalidateThenReadListing50(): Int = fixture.harness.request {
        val listing = fixture.listings[cursor++ % fixture.listings.size]
        val first = fixture.service.readListing(listing)
        listing.take(mutations.toInt()).forEach { fixture.service.removeFromCache(it) }
        first + fixture.service.readListing(listing)
    }
}
