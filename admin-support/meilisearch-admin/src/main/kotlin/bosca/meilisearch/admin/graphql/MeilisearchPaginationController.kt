package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchPagination

/**
 * Resolves fields on the MeilisearchPagination GraphQL type.
 */
@TypeController
class MeilisearchPaginationController : GraphQLController<MeilisearchPagination> {

    @Field fun maxTotalHits(p: MeilisearchPagination) = p.maxTotalHits
}
