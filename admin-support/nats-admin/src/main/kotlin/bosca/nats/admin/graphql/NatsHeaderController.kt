package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsHeader

/**
 * Resolves fields on the NatsHeader GraphQL type, exposing
 * header key-value pairs from NATS messages.
 */
@TypeController
class NatsHeaderController : GraphQLController<NatsHeader> {

    @Field
    fun key(header: NatsHeader) = header.key

    @Field
    fun values(header: NatsHeader): List<String> = header.values
}
