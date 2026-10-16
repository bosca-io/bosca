package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Persisted descriptive results. Null conditional fields mean no configured activation stage. */
@Serializable
data class GoalPopulationAnalysis(
    val cutoff: String,
    val activationConfigured: Boolean,
    val notApplicable: Boolean,
    val overall: GoalPopulation,
    val activated: GoalPopulation? = null,
    val notActivated: GoalPopulation? = null,
    val reach: Double? = null,
    val reachInterval: MetricInterval? = null,
    val placement: PlacementIntegrity? = null,
    val exposureEvents: Long? = null,
    val exposureIntensity: MetricDistribution? = null,
    val attribution: DestinationAttribution? = null,
    val diagnostics: GoalDiagnostics = GoalDiagnostics(),
)
