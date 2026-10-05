package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsKeyValueEntry

/**
 * Resolves fields on the NatsKeyValueEntry GraphQL type, exposing
 * individual key-value pairs from a NATS KeyValue store.
 */
@TypeController
class NatsKeyValueEntryController : GraphQLController<NatsKeyValueEntry> {

    @Field
    fun key(entry: NatsKeyValueEntry) = entry.key

    @Field
    fun value(entry: NatsKeyValueEntry) = entry.value

    @Field
    fun revision(entry: NatsKeyValueEntry) = entry.revision

    @Field
    fun created(entry: NatsKeyValueEntry) = entry.created

    @Field
    fun operation(entry: NatsKeyValueEntry) = entry.operation
}
