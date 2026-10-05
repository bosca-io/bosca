package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboardParameter
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsDashboardParameterController : GraphQLController<AnalyticsDashboardParameter> {

    @Field
    fun parameter(parameter: AnalyticsDashboardParameter) = parameter.parameter

    @Field
    fun name(parameter: AnalyticsDashboardParameter) = parameter.name

    @Field
    fun description(parameter: AnalyticsDashboardParameter) = parameter.description

    @Field
    fun type(parameter: AnalyticsDashboardParameter) = parameter.type

    @Field
    fun arrayType(parameter: AnalyticsDashboardParameter) = parameter.arrayType

    @Field
    fun defaultValue(parameter: AnalyticsDashboardParameter) = parameter.defaultValue

    @Field
    fun required(parameter: AnalyticsDashboardParameter) = parameter.required
}
