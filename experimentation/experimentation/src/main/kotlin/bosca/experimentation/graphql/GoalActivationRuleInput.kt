package bosca.experimentation.graphql

import bosca.experimentation.model.*
import kotlinx.serialization.Serializable

/** GraphQL event enums are translated before reaching the domain service. */
@Serializable
data class GoalActivationRuleInput(
    val variationKey: String,
    val mode: GoalActivationMode,
    val filter: ExperimentActivationFilterInput? = null,
)

/** Translates nested GraphQL event enums into the domain rule. */
fun GoalActivationRuleInput.toModel() = GoalActivationRule(variationKey, mode, filter?.toModel())
