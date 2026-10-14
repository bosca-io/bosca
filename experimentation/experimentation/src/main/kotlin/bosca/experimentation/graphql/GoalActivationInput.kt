package bosca.experimentation.graphql

import bosca.experimentation.model.*
import kotlinx.serialization.Serializable

/** Optional exposure configuration for one conversion goal. */
@Serializable
data class GoalActivationInput(
    val defaultFilter: ExperimentActivationFilterInput? = null,
    val variationRules: List<GoalActivationRuleInput>? = null,
    val sameSession: Boolean = false,
    val maxDelaySeconds: Int? = null,
    val applicationIds: List<String>? = null,
)

/** Translates GraphQL defaults and rule overrides into the domain activation. */
fun GoalActivationInput.toModel() = GoalActivation(defaultFilter?.toModel(), variationRules.orEmpty().map { it.toModel() },
    sameSession, maxDelaySeconds, applicationIds.orEmpty())
