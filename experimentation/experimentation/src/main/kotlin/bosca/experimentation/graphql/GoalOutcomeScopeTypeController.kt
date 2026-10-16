package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Resolves the persisted GoalOutcomeScope contract. */
@TypeController(type = "GoalOutcomeScope")
class GoalOutcomeScopeTypeController : GraphQLController<GoalOutcomeScope> {
    @Field fun applicationIds(value: GoalOutcomeScope): List<String> = value.applicationIds
    @Field fun sameSession(value: GoalOutcomeScope): Boolean = value.sameSession
    @Field fun maxDelaySeconds(value: GoalOutcomeScope): Int? = value.maxDelaySeconds
    @Field fun recommendationCard(value: GoalOutcomeScope): Boolean = value.recommendationCard
    @Field fun attributeRecommendationDestination(value: GoalOutcomeScope): Boolean = value.attributeRecommendationDestination
}
