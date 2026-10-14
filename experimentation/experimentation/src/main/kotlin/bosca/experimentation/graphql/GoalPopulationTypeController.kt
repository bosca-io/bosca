package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted GoalPopulation aggregates. */
@TypeController(type = "GoalPopulation")
class GoalPopulationTypeController : GraphQLController<GoalPopulation> {
    @Field fun subjects(value: GoalPopulation): Long = value.subjects
    @Field fun conversions(value: GoalPopulation): Long = value.conversions
    @Field fun observationCount(value: GoalPopulation): Long = value.observationCount
    @Field fun estimate(value: GoalPopulation): Double? = value.estimate
    @Field fun variance(value: GoalPopulation): Double? = value.variance
    @Field fun interval(value: GoalPopulation): MetricInterval? = value.interval
    @Field fun distribution(value: GoalPopulation): MetricDistribution? = value.distribution
}
