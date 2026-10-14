package bosca.experimentation.jobs

import bosca.experimentation.model.MetricInterval
import kotlinx.serialization.Serializable

/** Overall arm difference in metric units; conditional populations never enter this contract. */
@Serializable
data class PlannedComparison(
    val goalId: String, val leftVariationKey: String, val rightVariationKey: String,
    val leftSubjects: Long, val rightSubjects: Long,
    val difference: Double?, val interval: MetricInterval?, val evidence: Double?,
    val evidenceLabel: String, val threshold: Double, val conclusion: String, val reason: String,
)
