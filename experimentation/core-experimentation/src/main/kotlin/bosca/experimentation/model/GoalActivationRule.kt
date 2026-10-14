package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** A variation's override of the goal's default activation event. */
@Serializable
data class GoalActivationRule(
    val variationKey: String,
    val mode: GoalActivationMode,
    val filter: ExperimentActivationFilter? = null,
)
