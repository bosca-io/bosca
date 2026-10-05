package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchFaceting

/**
 * Resolves fields on the MeilisearchFaceting GraphQL type.
 */
@TypeController
class MeilisearchFacetingController : GraphQLController<MeilisearchFaceting> {

    @Field fun maxValuesPerFacet(f: MeilisearchFaceting) = f.maxValuesPerFacet
}
