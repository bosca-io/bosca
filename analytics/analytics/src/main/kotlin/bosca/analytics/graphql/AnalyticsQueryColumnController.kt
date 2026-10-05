package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryColumn
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves the scalar fields of the AnalyticsQueryColumn GraphQL type, which describes a single
 * output column of an analytics query (probed from its result-set metadata).
 */
@TypeController
class AnalyticsQueryColumnController : GraphQLController<AnalyticsQueryColumn> {

    @Field
    fun name(column: AnalyticsQueryColumn): String = column.name

    @Field
    fun typeName(column: AnalyticsQueryColumn): String = column.typeName

    @Field
    fun nullable(column: AnalyticsQueryColumn): Boolean = column.nullable
}
