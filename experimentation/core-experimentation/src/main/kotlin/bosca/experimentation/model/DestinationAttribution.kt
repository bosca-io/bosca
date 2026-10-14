package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Recommendation destination diagnostics count distinct logical interactions, not deliveries. */
@Serializable
data class DestinationAttribution(
    val engagedSubjects: Long = 0, val interactions: Long = 0,
    val attributedSubjects: Long = 0, val attributedInteractions: Long = 0,
    val interval: MetricInterval? = null,
    val unresolvedTargets: Long = 0, val unsupportedTypes: Long = 0, val missingSlugs: Long = 0,
    val missingSessions: Long = 0, val unmatchedDestinations: Long = 0,
)
