package bosca.counter

import bosca.service.Service

/**
 * A distributed, atomic integer counter keyed by an opaque string, safe across pods. Backed by NATS
 * (JetStream's server-side message counter) or Redis (`INCRBY`), selected by the `counter.type` config
 * — the same "NATS or Redis, by config" pattern as [bosca.cache.CacheManager] and
 * [bosca.pubsub.PubSubService], so domain code never binds to a concrete product.
 *
 * Built for high-write, aggregate-read metrics — e.g. API response codes bucketed per minute: increment
 * a key like `req.<service>.5xx.<minute>` on each response, then [get] a window of buckets to compute a
 * rate. The time bucket lives in the key; there is no per-event row and no rollup job. Backends give
 * counters a bounded retention so old buckets fall out on their own.
 */
interface Counter : Service {
    /** Atomically add [by] (may be negative) to the counter at [key] and return the new total. */
    suspend fun increment(key: String, by: Long = 1): Long

    /** The current value at [key], or `0` when it was never incremented (or has since expired). */
    suspend fun get(key: String): Long

    /** The current values for [keys] — absent keys map to `0`. One read for a whole window of buckets. */
    suspend fun get(keys: List<String>): Map<String, Long>
}
