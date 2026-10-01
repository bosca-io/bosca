package bosca.analytics.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

object Analytics

@TypeController
class AnalyticsController() : GraphQLController<Analytics> {

    @Field
    fun queries() = AnalyticsQueries

    @Field
    fun visualizations() = AnalyticsVisualizations

    @Field
    fun dashboards() = AnalyticsDashboards

    @Field
    fun errors() = AnalyticsErrors

    @Field
    fun counters() = AnalyticsCounters

    @Field
    fun scriptBindings() = AnalyticsScriptBindings
}