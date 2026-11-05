package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryEvidence
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsQueryEvidenceController : GraphQLController<AnalyticsQueryEvidence> {
    @Field fun queryId(source: AnalyticsQueryEvidence) = source.queryId
    @Field fun sql(source: AnalyticsQueryEvidence) = source.sql
    @Field fun parameters(source: AnalyticsQueryEvidence) = source.parameters
    @Field fun executedAt(source: AnalyticsQueryEvidence) = source.executedAt
    @Field fun durationMs(source: AnalyticsQueryEvidence) = source.durationMs
}
