package bosca.analytics.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

object AnalyticsMutation

@TypeController
class AnalyticsMutationController : GraphQLController<AnalyticsMutation> {

    @Field
    fun queries() = AnalyticsQueriesMutation

    @Field
    fun visualizations() = AnalyticsVisualizationsMutation

    @Field
    fun dashboards() = AnalyticsDashboardsMutation

    @Field
    fun errors() = AnalyticsErrorsMutation

    @Field
    fun scriptBindings() = AnalyticsScriptBindingsMutation
}
