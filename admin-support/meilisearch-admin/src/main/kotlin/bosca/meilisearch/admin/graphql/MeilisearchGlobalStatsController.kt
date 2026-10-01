package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchGlobalStats

/**
 * Resolves fields on the MeilisearchGlobalStats GraphQL type.
 */
@TypeController
class MeilisearchGlobalStatsController : GraphQLController<MeilisearchGlobalStats> {

    @Field
    fun numberOfIndexes(stats: MeilisearchGlobalStats) = stats.numberOfIndexes

    @Field
    fun databaseSize(stats: MeilisearchGlobalStats) = stats.databaseSize

    @Field
    fun lastUpdate(stats: MeilisearchGlobalStats) = stats.lastUpdate
}
