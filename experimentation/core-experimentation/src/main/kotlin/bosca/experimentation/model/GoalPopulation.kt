package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Explicit population denominator and its outcome, independent of the randomized decision. */
@Serializable
data class GoalPopulation(
    val subjects: Long,
    val conversions: Long,
    val observationCount: Long,
    val estimate: Double?,
    val variance: Double?,
    val interval: MetricInterval?,
    val distribution: MetricDistribution? = null,
)
