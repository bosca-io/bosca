package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryBinding
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsQueryBindingController : GraphQLController<AnalyticsQueryBinding> {
    @Field fun position(source: AnalyticsQueryBinding) = source.position
    @Field fun parameter(source: AnalyticsQueryBinding) = source.parameter
    @Field fun type(source: AnalyticsQueryBinding) = source.type
    @Field fun value(source: AnalyticsQueryBinding) = source.value
}
