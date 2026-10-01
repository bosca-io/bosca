package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryParameter
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class AnalyticsQueryParameterController : GraphQLController<AnalyticsQueryParameter> {

    @Field
    fun parameter(parameter: AnalyticsQueryParameter) = parameter.parameter

    @Field
    fun name(parameter: AnalyticsQueryParameter) = parameter.name

    @Field
    fun description(parameter: AnalyticsQueryParameter) = parameter.description

    @Field
    fun type(parameter: AnalyticsQueryParameter) = parameter.type

    @Field
    fun arrayType(parameter: AnalyticsQueryParameter) = parameter.arrayType

    @Field
    fun defaultValue(parameter: AnalyticsQueryParameter) = parameter.defaultValue

    @Field
    fun required(parameter: AnalyticsQueryParameter) = parameter.required

    @Field
    fun sort(parameter: AnalyticsQueryParameter) = parameter.sort
}
