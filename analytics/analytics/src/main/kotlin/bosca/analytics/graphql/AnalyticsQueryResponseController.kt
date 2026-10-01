package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryResponse
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsQueryResponseController : GraphQLController<AnalyticsQueryResponse> {

    @Field
    fun records(response: AnalyticsQueryResponse) = response.records

    @Field
    fun cached(response: AnalyticsQueryResponse) = response.cached

    @Field
    fun stale(response: AnalyticsQueryResponse) = response.stale

    @Field
    fun refreshedAt(response: AnalyticsQueryResponse) = response.refreshedAt
}
