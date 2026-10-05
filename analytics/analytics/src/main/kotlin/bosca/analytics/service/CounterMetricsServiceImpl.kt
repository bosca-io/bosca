package bosca.analytics.service

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.model.AnalyticsCounterType
import bosca.analytics.model.AnalyticsCounterValue
import bosca.counter.Counter
import bosca.serialization.OffsetDateTime
import bosca.service.annotation.ServiceImplementation
import java.time.Instant
import java.time.ZoneOffset

/**
 * Projects the distributed [Counter] into typed, windowed metrics for the release dashboard.
 *
 * A counter id (e.g. `sessions.mobile`, `http.bosca.5xx`) is matched against [FAMILIES] — the built-in
 * registry of counter shapes the platform writes. A family fixes the aggregation type, the bucket size
 * (which **must** match the write side that produced the keys), and a default lookback. A read computes
 * the range of bucket indices covering the window, fetches every `"<id>.<bucket>"` key in one batch
 * ([Counter.get]), and either aggregates them ([value]) or zero-fills them into a dense series ([values]).
 *
 * The class is `open` solely to let tests substitute [nowEpochSeconds]; DI constructs it with just the
 * [counter] (the generated provider resolves every constructor parameter, so the clock cannot be a
 * defaulted constructor argument).
 */
@ServiceImplementation
open class CounterMetricsServiceImpl(
    private val counter: Counter,
) : CounterMetricsService {

    /** Current wall-clock in epoch seconds. Overridable in tests; never a constructor arg (see class KDoc). */
    protected open fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    override suspend fun counter(id: String): AnalyticsCounter? =
        familyOf(id)?.let { AnalyticsCounter(id, it.type) }

    override suspend fun value(id: String, window: Int?): Long {
        val family = familyOf(id) ?: return 0L
        val counts = counter.get(bucketRange(family, window).map { "$id.$it" })
        return when (family.type) {
            AnalyticsCounterType.GAUGE -> counts.values.maxOrNull() ?: 0L
            AnalyticsCounterType.COUNTER -> counts.values.sum()
        }
    }

    override suspend fun values(id: String, window: Int?): List<AnalyticsCounterValue> {
        val family = familyOf(id) ?: return emptyList()
        val range = bucketRange(family, window)
        val counts = counter.get(range.map { "$id.$it" })
        return range.map { bucket ->
            AnalyticsCounterValue(
                time = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(bucket * family.bucketSeconds),
                    ZoneOffset.UTC,
                ),
                value = counts["$id.$bucket"] ?: 0L,
            )
        }
    }

    /** The inclusive range of bucket indices ending at the current bucket, [window] (clamped) buckets wide. */
    private fun bucketRange(family: CounterFamily, window: Int?): LongRange {
        val current = nowEpochSeconds() / family.bucketSeconds
        val span = (window ?: family.defaultWindow).coerceIn(1, MAX_WINDOW).toLong()
        return (current - span + 1)..current
    }

    private fun familyOf(id: String): CounterFamily? = FAMILIES.firstOrNull { id.startsWith(it.prefix) }

    /**
     * A built-in counter family. Every counter whose id starts with [prefix] shares an aggregation [type],
     * a [bucketSeconds] granularity, and a [defaultWindow] lookback (bucket count) for un-parameterized
     * reads. [bucketSeconds] must equal the bucket size used by the code that writes the keys.
     */
    private data class CounterFamily(
        val prefix: String,
        val type: AnalyticsCounterType,
        val bucketSeconds: Long,
        val defaultWindow: Int,
    )

    companion object {
        /** Upper bound on a requested window, so a caller can't fan out an unbounded set of bucket keys. */
        const val MAX_WINDOW = 1_440

        /**
         * The counter families the platform writes. Prefixes and bucket sizes must stay in lockstep with
         * the write sides:
         * - `sessions.*` — [bosca.analytics.transform.SessionHeartbeatTransform], 15-minute buckets. A
         *   session emits one heartbeat per bucket, so a bucket's value is the active-session count; the
         *   default 2-bucket `GAUGE` window reads `max(current, previous)` to tolerate a partial bucket.
         * - `http.*` — `ResponseCounterMiddleware`, 1-minute buckets. `COUNTER` sums; the default 60-bucket
         *   window totals the last hour of responses for a status class.
         */
        private val FAMILIES = listOf(
            CounterFamily("sessions.", AnalyticsCounterType.GAUGE, bucketSeconds = 15L * 60, defaultWindow = 2),
            CounterFamily("http.", AnalyticsCounterType.COUNTER, bucketSeconds = 60, defaultWindow = 60),
        )
    }
}
