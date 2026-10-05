package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchHealth

/**
 * Resolves fields on the MeilisearchHealth GraphQL type.
 */
@TypeController
class MeilisearchHealthController : GraphQLController<MeilisearchHealth> {

    @Field fun healthy(health: MeilisearchHealth) = health.healthy
}
