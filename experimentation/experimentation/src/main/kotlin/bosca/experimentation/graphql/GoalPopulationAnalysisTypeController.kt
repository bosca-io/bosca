package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted GoalPopulationAnalysis aggregates. */
@TypeController(type = "GoalPopulationAnalysis")
class GoalPopulationAnalysisTypeController : GraphQLController<GoalPopulationAnalysis> {
    @Field fun cutoff(value: GoalPopulationAnalysis): String = value.cutoff
    @Field fun activationConfigured(value: GoalPopulationAnalysis): Boolean = value.activationConfigured
    @Field fun notApplicable(value: GoalPopulationAnalysis): Boolean = value.notApplicable
    @Field fun overall(value: GoalPopulationAnalysis): GoalPopulation = value.overall
    @Field fun activated(value: GoalPopulationAnalysis): GoalPopulation? = value.activated
    @Field fun notActivated(value: GoalPopulationAnalysis): GoalPopulation? = value.notActivated
    @Field fun reach(value: GoalPopulationAnalysis): Double? = value.reach
    @Field fun reachInterval(value: GoalPopulationAnalysis): MetricInterval? = value.reachInterval
    @Field fun placement(value: GoalPopulationAnalysis): PlacementIntegrity? = value.placement
    @Field fun exposureEvents(value: GoalPopulationAnalysis): Long? = value.exposureEvents
    @Field fun exposureIntensity(value: GoalPopulationAnalysis): MetricDistribution? = value.exposureIntensity
    @Field fun attribution(value: GoalPopulationAnalysis): DestinationAttribution? = value.attribution
    @Field fun diagnostics(value: GoalPopulationAnalysis): GoalDiagnostics = value.diagnostics
}
