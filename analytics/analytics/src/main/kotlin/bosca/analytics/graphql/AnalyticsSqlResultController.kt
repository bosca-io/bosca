package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsSqlResult
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsSqlResultController : GraphQLController<AnalyticsSqlResult> {
    @Field fun sql(source: AnalyticsSqlResult) = source.sql
    @Field fun columns(source: AnalyticsSqlResult) = source.columns
    @Field fun records(source: AnalyticsSqlResult) = source.records
    @Field fun truncated(source: AnalyticsSqlResult) = source.truncated
    @Field fun executedAt(source: AnalyticsSqlResult) = source.executedAt
    @Field fun durationMs(source: AnalyticsSqlResult) = source.durationMs
}
