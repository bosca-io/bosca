package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Exact per-subject distribution; count excludes subjects without a continuous observation. */
@Serializable
data class MetricDistribution(
    val count: Long, val mean: Double, val standardDeviation: Double,
    val p50: Double, val p75: Double, val p90: Double, val p95: Double, val p99: Double, val maximum: Double,
)
