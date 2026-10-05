package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchFieldDistribution

/**
 * Resolves fields on the MeilisearchFieldDistribution GraphQL type.
 */
@TypeController
class MeilisearchFieldDistributionController : GraphQLController<MeilisearchFieldDistribution> {

    @Field fun field(entry: MeilisearchFieldDistribution) = entry.field
    @Field fun count(entry: MeilisearchFieldDistribution) = entry.count
}
