package bosca.analytics.service

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.model.AnalyticsCounterValue
import bosca.service.Service

/**
 * Read access to the platform's distributed [bosca.counter.Counter] telemetry, projected as typed,
 * windowed metrics for the release dashboard (active sessions, API response-code rates).
 *
 * A counter [id] is the key prefix that precedes a counter's per-bucket time index — e.g. `sessions.mobile`
 * or `http.bosca.5xx`. The service resolves that id against a built-in registry of counter *families*,
 * each of which fixes the aggregation ([bosca.analytics.model.AnalyticsCounterType]), the bucket
 * granularity (which must match the write side), and a default lookback window. Reads never mutate; they
 * fetch a window of bucket keys from the underlying counter in a single batch and aggregate in memory.
 */
interface CounterMetricsService : Service {

    /**
     * Resolve [id] to its typed counter via the built-in family registry, or `null` when no family's
     * prefix matches (an unknown counter). Does not touch the counter store.
     */
    suspend fun counter(id: String): AnalyticsCounter?

    /**
     * The aggregate value of [id] over the last [window] buckets — `GAUGE` returns the peak bucket,
     * `COUNTER` the sum. [window] is a bucket count; when `null` the family's default lookback is used.
     * Returns `0` for an unknown counter.
     */
    suspend fun value(id: String, window: Int?): Long

    /**
     * A dense, zero-filled per-bucket time series for [id] over the last [window] buckets, ordered
     * oldest → newest, for graphing. [window] is a bucket count; `null` uses the family default. Returns
     * an empty list for an unknown counter.
     */
    suspend fun values(id: String, window: Int?): List<AnalyticsCounterValue>
}
