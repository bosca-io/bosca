package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the persisted GoalActivationRule contract. */
@TypeController(type = "GoalActivationRule")
class GoalActivationRuleTypeController : GraphQLController<GoalActivationRule> {
    @Field fun variationKey(value: GoalActivationRule): String = value.variationKey
    @Field fun mode(value: GoalActivationRule): GoalActivationMode = value.mode
    @Field fun filter(value: GoalActivationRule): ExperimentActivationFilter? = value.filter
}
