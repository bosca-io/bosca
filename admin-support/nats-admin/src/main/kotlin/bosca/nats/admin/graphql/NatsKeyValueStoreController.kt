package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsKeyValueStore

/**
 * Resolves fields on the NatsKeyValueStore GraphQL type, exposing
 * metadata about a NATS KeyValue store bucket.
 */
@TypeController
class NatsKeyValueStoreController : GraphQLController<NatsKeyValueStore> {

    @Field
    fun bucket(store: NatsKeyValueStore) = store.bucket

    @Field
    fun entryCount(store: NatsKeyValueStore) = store.entryCount

    @Field
    fun bytes(store: NatsKeyValueStore) = store.bytes

    @Field
    fun ttl(store: NatsKeyValueStore) = store.ttl

    @Field
    fun maxBytes(store: NatsKeyValueStore) = store.maxBytes

    @Field
    fun maxValueSize(store: NatsKeyValueStore) = store.maxValueSize

    @Field
    fun history(store: NatsKeyValueStore) = store.history
}
