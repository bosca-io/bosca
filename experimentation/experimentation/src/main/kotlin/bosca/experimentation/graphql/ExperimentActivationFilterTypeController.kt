package bosca.experimentation.graphql

import bosca.experimentation.model.ExperimentActivationFilter
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes the configured experiment activation event filter. */
@TypeController(type = "ExperimentActivationFilter")
class ExperimentActivationFilterTypeController : GraphQLController<ExperimentActivationFilter> {

    @Field
    fun eventType(filter: ExperimentActivationFilter): GraphQLEventType? =
        filter.eventType?.let(GraphQLEventType::fromEventType)

    @Field
    fun elementType(filter: ExperimentActivationFilter): String? = filter.elementType

    @Field
    fun elementId(filter: ExperimentActivationFilter): String? = filter.elementId

    @Field
    fun pagePath(filter: ExperimentActivationFilter): String? = filter.pagePath

    @Field
    fun pagePathPrefixes(filter: ExperimentActivationFilter): List<String> = filter.pagePathPrefixes

    @Field
    fun itemExtraKey(filter: ExperimentActivationFilter): String? = filter.itemExtraKey

    @Field
    fun itemExtraValue(filter: ExperimentActivationFilter): String? = filter.itemExtraValue
}
