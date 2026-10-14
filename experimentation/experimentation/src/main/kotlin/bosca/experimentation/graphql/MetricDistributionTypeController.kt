package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted MetricDistribution aggregates. */
@TypeController(type = "MetricDistribution")
class MetricDistributionTypeController : GraphQLController<MetricDistribution> {
    @Field fun count(value: MetricDistribution): Long = value.count
    @Field fun mean(value: MetricDistribution): Double = value.mean
    @Field fun standardDeviation(value: MetricDistribution): Double = value.standardDeviation
    @Field fun p50(value: MetricDistribution): Double = value.p50
    @Field fun p75(value: MetricDistribution): Double = value.p75
    @Field fun p90(value: MetricDistribution): Double = value.p90
    @Field fun p95(value: MetricDistribution): Double = value.p95
    @Field fun p99(value: MetricDistribution): Double = value.p99
    @Field fun maximum(value: MetricDistribution): Double = value.maximum
}
