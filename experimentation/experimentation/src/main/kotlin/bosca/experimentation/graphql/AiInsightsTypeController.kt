package bosca.experimentation.graphql

import bosca.experimentation.model.AiInsights
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves fields on the AiInsights GraphQL type. The framework does not
 * autobind type fields from data class properties, so every exposed
 * field needs an explicit `@Field` resolver here — without this the
 * four sub-fields on `AnalysisReport.aiInsights` silently fail to
 * resolve and the admin UI hides the whole AI commentary block.
 */
@TypeController(type = "AiInsights")
class AiInsightsTypeController : GraphQLController<AiInsights> {

    @Field
    fun hypothesisAssessment(insights: AiInsights): String = insights.hypothesisAssessment

    @Field
    fun crossGoalPatterns(insights: AiInsights): String = insights.crossGoalPatterns

    @Field
    fun followUpExperiments(insights: AiInsights): List<String> = insights.followUpExperiments

    @Field
    fun srmRootCauseHints(insights: AiInsights): String? = insights.srmRootCauseHints
}
