package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchNetworkRemote

/**
 * Resolves fields on the MeilisearchNetworkRemote GraphQL type.
 */
@TypeController
class MeilisearchNetworkRemoteController : GraphQLController<MeilisearchNetworkRemote> {

    @Field fun name(remote: MeilisearchNetworkRemote) = remote.name
    @Field fun url(remote: MeilisearchNetworkRemote) = remote.url
}
