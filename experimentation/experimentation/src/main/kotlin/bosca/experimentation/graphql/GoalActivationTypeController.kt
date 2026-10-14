package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the persisted GoalActivation contract. */
@TypeController(type = "GoalActivation")
class GoalActivationTypeController : GraphQLController<GoalActivation> {
    @Field fun defaultFilter(value: GoalActivation): ExperimentActivationFilter? = value.defaultFilter
    @Field fun variationRules(value: GoalActivation): List<GoalActivationRule> = value.variationRules
    @Field fun sameSession(value: GoalActivation): Boolean = value.sameSession
    @Field fun maxDelaySeconds(value: GoalActivation): Int? = value.maxDelaySeconds
    @Field fun applicationIds(value: GoalActivation): List<String> = value.applicationIds
}
