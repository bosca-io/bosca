package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsVisualizationInstanceController : GraphQLController<AnalyticsVisualizationInstance> {

    @Field
    fun id(instance: AnalyticsVisualizationInstance) = instance.id

    @Field
    fun configuration(instance: AnalyticsVisualizationInstance) = instance.configuration

    @Field
    fun visualization(instance: AnalyticsVisualizationInstance) = instance.visualization
}
