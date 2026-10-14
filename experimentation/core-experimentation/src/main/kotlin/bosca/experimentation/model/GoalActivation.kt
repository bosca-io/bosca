package bosca.experimentation.model

import kotlinx.serialization.Serializable

/**
 * Descriptive exposure boundary within the experiment's eligible population.
 * Missing application ids inherit the application of the experiment activation event.
 * Activation never changes the overall denominator or the automatic decision population.
 */
@Serializable
data class GoalActivation(
    val defaultFilter: ExperimentActivationFilter? = null,
    val variationRules: List<GoalActivationRule> = emptyList(),
    val sameSession: Boolean = false,
    val maxDelaySeconds: Int? = null,
    val applicationIds: List<String> = emptyList(),
) {
    /** Resolves the effective event; null means no conditional activation stage. */
    fun filterFor(variationKey: String): ExperimentActivationFilter? {
        val rule = variationRules.firstOrNull { it.variationKey == variationKey }
        return when (rule?.mode) {
            GoalActivationMode.EVENT -> rule.filter
            GoalActivationMode.NOT_APPLICABLE -> null
            GoalActivationMode.INHERIT, null -> defaultFilter
        }
    }
}
