package bosca.content.healthcheck

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * GraphQL controller that resolves the count field on the ContentHealthCheckCount type,
 * providing the numeric value for health check summary statistics.
 */
@TypeController
class ContentHealthCheckCountController : GraphQLController<ContentHealthCheckCount> {

    @Field
    fun count(item: ContentHealthCheckCount): Int = item.count
}
