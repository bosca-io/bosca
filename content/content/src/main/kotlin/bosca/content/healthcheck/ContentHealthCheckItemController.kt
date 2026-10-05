package bosca.content.healthcheck

import bosca.content.metadata.model.ContentHealthCheckItem
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/**
 * GraphQL controller that resolves the scalar fields on the ContentHealthCheckItem type,
 * mapping each property of the underlying data class to a GraphQL field data fetcher.
 */
@TypeController
class ContentHealthCheckItemController : GraphQLController<ContentHealthCheckItem> {

    @Field
    fun id(item: ContentHealthCheckItem): UUID = item.id

    @Field
    fun name(item: ContentHealthCheckItem): String = item.name

    @Field
    fun workflowState(item: ContentHealthCheckItem): String = item.workflowState
}
