package bosca.graphql.server

import bosca.graphql.language.Document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A parse (and validation) result for one query string: the [document], or the [errors] that prevented it. Cached by
 * a [PreparsedDocumentProvider] so a repeated query — including a persisted query referenced by hash — skips re-parse.
 */
class PreparsedDocument(val document: Document?, val errors: List<GraphQLError> = emptyList()) {
    companion object {
        fun of(document: Document) = PreparsedDocument(document)
        fun ofErrors(errors: List<GraphQLError>) = PreparsedDocument(null, errors)
    }
}

/**
 * Supplies the parsed [PreparsedDocument] for a query, equivalent to graphql-java's `execution.preparsed`
 * — the persisted-query cache. [document] is called with a [parse] function and may return a cached result instead of
 * invoking it, avoiding a re-parse for queries seen before.
 */
fun interface PreparsedDocumentProvider {
    suspend fun document(query: String, parse: suspend (String) -> PreparsedDocument): PreparsedDocument

    companion object {
        /** No caching — always parses. */
        val NONE = PreparsedDocumentProvider { query, parse -> parse(query) }
    }
}

/**
 * A [PreparsedDocumentProvider] whose cached entries can be explicitly removed after request-specific rejection.
 */
interface InvalidatablePreparsedDocumentProvider : PreparsedDocumentProvider {
    /** Remove [query] from this provider, if it is cached. */
    suspend fun invalidate(query: String)
}

/**
 * A bounded in-memory [PreparsedDocumentProvider] caching parse/validation results by query string. The
 * cache uses lock-free read snapshots and a second-chance eviction clock, so hits do not serialize on the miss/write
 * mutex. A cold entry is evicted when either [maxEntries] or [maxCachedQueryBytes] is exceeded, validation failures
 * are retained to avoid repeating attacker-controlled validation work, and concurrent requests for the same cache
 * miss share one parse/validation operation.
 * At most [maxConcurrentParses] distinct cache misses parse at once. Optionally [preload] a static allowlist of
 * `query -> parsed` documents.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryPreparsedDocumentProvider(
    preload: Map<String, Document> = emptyMap(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxCachedQueryBytes: Long = DEFAULT_MAX_CACHED_QUERY_BYTES,
    maxConcurrentParses: Int = DEFAULT_MAX_CONCURRENT_PARSES,
) : InvalidatablePreparsedDocumentProvider {
    private val cache = AtomicReference<Map<String, CachedDocument>>(emptyMap())
    private val evictionClock = ArrayDeque<String>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<PreparsedDocument>>()
    private val mutex = Mutex()
    private val parsePermits: Semaphore
    private var cachedQueryBytes = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxCachedQueryBytes > 0) { "maxCachedQueryBytes must be positive" }
        require(maxConcurrentParses > 0) { "maxConcurrentParses must be positive" }
        parsePermits = Semaphore(maxConcurrentParses)
        preload.forEach { (query, document) -> putBounded(query, PreparsedDocument.of(document)) }
    }

    override suspend fun document(query: String, parse: suspend (String) -> PreparsedDocument): PreparsedDocument {
        cached(query)?.let { return it }
        mutex.withLock { inFlight[query] }?.let { return it.await() }

        parsePermits.acquire()
        val candidate = CompletableDeferred<PreparsedDocument>()
        val reservation = withContext(NonCancellable) {
            mutex.withLock {
                when (val current = lookupExisting(query)) {
                    is ExistingLookup.Cached -> Reservation.Cached(current.document)
                    is ExistingLookup.Pending -> Reservation.Pending(current.result)
                    null -> {
                        inFlight[query] = candidate
                        Reservation.Owner
                    }
                }
            }
        }
        if (reservation !is Reservation.Owner) parsePermits.release()

        return when (reservation) {
            is Reservation.Cached -> reservation.document
            is Reservation.Pending -> reservation.result.await()
            Reservation.Owner -> {
                try {
                    val parsed = parse(query)
                    withContext(NonCancellable) {
                        mutex.withLock {
                            inFlight.remove(query)
                            putBounded(query, parsed)
                        }
                        candidate.complete(parsed)
                    }
                    parsed
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        mutex.withLock { inFlight.remove(query) }
                        candidate.completeExceptionally(error)
                    }
                    throw error
                } finally {
                    parsePermits.release()
                }
            }
        }
    }

    override suspend fun invalidate(query: String) {
        mutex.withLock { removeCached(query) }
    }

    /** The number of cached documents — for tests/metrics. */
    val size: Int get() = cache.load().size

    /** Lock-free cache hit path. Recency is recorded on the entry rather than by mutating the cache map. */
    private fun cached(query: String): PreparsedDocument? =
        cache.load()[query]?.let { cached ->
            cached.markRecentlyUsed()
            cached.document
        }

    /** Inspect a cache miss or in-flight parse. Caller holds [mutex]. */
    private fun lookupExisting(query: String): ExistingLookup? {
        cached(query)?.let { return ExistingLookup.Cached(it) }
        inFlight[query]?.let { return ExistingLookup.Pending(it) }
        return null
    }

    /**
     * Insert one result and evict entries with a second-chance clock. Cache hits never take [mutex]; they mark their
     * entry as recently used, and eviction clears that mark once before considering the entry removable.
     *
     * Caller holds [mutex], or is in init.
     */
    private fun putBounded(query: String, document: PreparsedDocument) {
        val weight = query.encodeToByteArray().size.toLong()
        val updated = cache.load().toMutableMap()
        removeCached(updated, query)
        if (weight <= maxCachedQueryBytes) {
            while (updated.size >= maxEntries || cachedQueryBytes + weight > maxCachedQueryBytes) {
                evictOne(updated)
            }
            updated[query] = CachedDocument(document, weight)
            evictionClock.addLast(query)
            cachedQueryBytes += weight
        }
        cache.store(updated.toMap())
    }

    /** Remove one cached entry and publish the new snapshot. Caller holds [mutex]. */
    private fun removeCached(query: String) {
        val updated = cache.load().toMutableMap()
        if (removeCached(updated, query)) {
            cache.store(updated.toMap())
        }
    }

    /** Remove [query] from a mutable write snapshot and the eviction ring. */
    private fun removeCached(updated: MutableMap<String, CachedDocument>, query: String): Boolean {
        val removed = updated.remove(query) ?: return false
        evictionClock.remove(query)
        cachedQueryBytes -= removed.queryBytes
        return true
    }

    /** Evict one live entry, giving recently-used entries one pass around the clock first. */
    private fun evictOne(updated: MutableMap<String, CachedDocument>) {
        while (true) {
            val query = evictionClock.removeFirst()
            val candidate = updated.getValue(query)
            if (candidate.clearRecentlyUsed()) {
                evictionClock.addLast(query)
                continue
            }
            updated.remove(query)
            cachedQueryBytes -= candidate.queryBytes
            return
        }
    }

    private class CachedDocument(
        val document: PreparsedDocument,
        val queryBytes: Long,
    ) {
        private val recentlyUsed = AtomicInt(0)

        fun markRecentlyUsed() {
            if (recentlyUsed.load() == 0) recentlyUsed.compareAndSet(0, 1)
        }

        /** Returns true when this entry received a second chance and should remain in the eviction ring. */
        fun clearRecentlyUsed(): Boolean = recentlyUsed.compareAndSet(1, 0)
    }

    private sealed interface ExistingLookup {
        data class Cached(val document: PreparsedDocument) : ExistingLookup
        data class Pending(val result: CompletableDeferred<PreparsedDocument>) : ExistingLookup
    }

    private sealed interface Reservation {
        data class Cached(val document: PreparsedDocument) : Reservation
        data class Pending(val result: CompletableDeferred<PreparsedDocument>) : Reservation
        data object Owner : Reservation
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 1_024
        const val DEFAULT_MAX_CACHED_QUERY_BYTES: Long = 16L * 1_024L * 1_024L
        const val DEFAULT_MAX_CONCURRENT_PARSES: Int = 128
    }
}
