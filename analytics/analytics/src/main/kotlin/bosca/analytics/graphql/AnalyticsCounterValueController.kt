package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounterValue
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field resolver for the GraphQL `AnalyticsCounterValue` type — a pure projection of one point in a
 * counter's time series.
 */
@TypeController
class AnalyticsCounterValueController : GraphQLController<AnalyticsCounterValue> {

    @Field
    fun time(sample: AnalyticsCounterValue) = sample.time

    @Field
    fun value(sample: AnalyticsCounterValue) = sample.value
}
