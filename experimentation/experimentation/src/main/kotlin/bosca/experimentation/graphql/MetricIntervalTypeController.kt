package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted MetricInterval aggregates. */
@TypeController(type = "MetricInterval")
class MetricIntervalTypeController : GraphQLController<MetricInterval> {
    @Field fun lower(value: MetricInterval): Double = value.lower
    @Field fun upper(value: MetricInterval): Double = value.upper
}
