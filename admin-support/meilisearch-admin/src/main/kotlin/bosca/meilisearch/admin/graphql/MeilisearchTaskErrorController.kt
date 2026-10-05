package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchTaskError

/**
 * Resolves fields on the MeilisearchTaskError GraphQL type.
 */
@TypeController
class MeilisearchTaskErrorController : GraphQLController<MeilisearchTaskError> {

    @Field fun message(error: MeilisearchTaskError) = error.message
    @Field fun code(error: MeilisearchTaskError) = error.code
    @Field fun type(error: MeilisearchTaskError) = error.type
    @Field fun link(error: MeilisearchTaskError) = error.link
}
