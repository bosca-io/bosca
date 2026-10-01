package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchFieldDistribution
import bosca.meilisearch.admin.model.MeilisearchIndexStats

/**
 * Resolves fields on the MeilisearchIndexStats GraphQL type.
 */
@TypeController
class MeilisearchIndexStatsController : GraphQLController<MeilisearchIndexStats> {

    @Field
    fun numberOfDocuments(stats: MeilisearchIndexStats) = stats.numberOfDocuments

    @Field
    fun isIndexing(stats: MeilisearchIndexStats) = stats.isIndexing

    @Field
    fun fieldDistribution(stats: MeilisearchIndexStats): List<MeilisearchFieldDistribution> =
        stats.fieldDistribution
}
