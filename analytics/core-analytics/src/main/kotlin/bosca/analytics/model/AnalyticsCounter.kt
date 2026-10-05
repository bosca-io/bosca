package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Serializable

/**
 * How an [AnalyticsCounter] collapses its per-bucket samples into a single window value.
 *
 * The type is intrinsic to a counter *family* (resolved server-side from the counter id), not something
 * the caller chooses — a client can never ask for a meaningless aggregation (e.g. summing a level).
 */
enum class AnalyticsCounterType {
    /** A level that rises and falls; the window value is the peak bucket (e.g. active sessions). */
    GAUGE,

    /** A monotonic tally; the window value is the sum of the window's buckets (e.g. API response codes). */
    COUNTER,
}

/**
 * A read-side view of a distributed [bosca.counter.Counter], addressed by the key prefix that precedes its
 * per-bucket time index (e.g. `sessions.mobile`, `http.bosca.5xx`). The backing counter stores one value per
 * time bucket; this projection exposes a typed window aggregate ([AnalyticsCounterType]) and a per-bucket
 * time series for graphing. Field reads are resolved lazily by the GraphQL controller, so this carries only
 * the resolved identity.
 */
@Serializable
data class AnalyticsCounter(
    val id: String,
    val type: AnalyticsCounterType,
)

/**
 * One point in a counter's time series: the value accumulated in a single bucket, stamped at the bucket's
 * start (UTC). Buckets with no samples are reported as `0` so the series is dense and directly graphable.
 */
@Serializable
data class AnalyticsCounterValue(
    val time: OffsetDateTime,
    val value: Long,
)
