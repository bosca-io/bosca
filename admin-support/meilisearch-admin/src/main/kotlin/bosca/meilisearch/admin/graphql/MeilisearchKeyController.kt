package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchKey

/**
 * Resolves fields on the MeilisearchKey GraphQL type.
 */
@TypeController
class MeilisearchKeyController : GraphQLController<MeilisearchKey> {

    @Field fun name(key: MeilisearchKey) = key.name
    @Field fun description(key: MeilisearchKey) = key.description
    @Field fun uid(key: MeilisearchKey) = key.uid
    @Field fun key(key: MeilisearchKey): String {
        return "****"
    }
    @Field fun actions(key: MeilisearchKey) = key.actions
    @Field fun indexes(key: MeilisearchKey) = key.indexes
    @Field fun expiresAt(key: MeilisearchKey) = key.expiresAt
    @Field fun createdAt(key: MeilisearchKey) = key.createdAt
    @Field fun updatedAt(key: MeilisearchKey) = key.updatedAt
}
