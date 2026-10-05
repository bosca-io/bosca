package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsCounter
import bosca.analytics.model.AnalyticsCounterValue
import bosca.analytics.service.CounterMetricsService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field resolver for the GraphQL `AnalyticsCounter` type. `id` and `type` are already resolved on the
 * carried [AnalyticsCounter]; `value` and `values` read the underlying counter store on demand via
 * [CounterMetricsService], applying the type-driven aggregation and lookback.
 */
@TypeController
class AnalyticsCounterController(
    private val counterMetricsService: CounterMetricsService,
) : GraphQLController<AnalyticsCounter> {

    @Field
    fun id(counter: AnalyticsCounter) = counter.id

    @Field
    fun type(counter: AnalyticsCounter) = counter.type

    @Field
    suspend fun value(counter: AnalyticsCounter, window: Int? = null): Long =
        counterMetricsService.value(counter.id, window)

    @Field
    suspend fun values(counter: AnalyticsCounter, window: Int? = null): List<AnalyticsCounterValue> =
        counterMetricsService.values(counter.id, window)
}
