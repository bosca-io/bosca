package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamApiStats
import bosca.nats.admin.model.JetStreamInfo

/**
 * Resolves fields on the JetStreamInfo GraphQL type, exposing
 * JetStream overview statistics from the NATS `/jsz` endpoint.
 */
@TypeController
class JetStreamInfoController : GraphQLController<JetStreamInfo> {

    @Field
    fun memory(info: JetStreamInfo) = info.memory

    @Field
    fun storage(info: JetStreamInfo) = info.storage

    @Field
    fun reservedMemory(info: JetStreamInfo) = info.reservedMemory

    @Field
    fun reservedStorage(info: JetStreamInfo) = info.reservedStorage

    @Field
    fun accounts(info: JetStreamInfo) = info.accounts

    @Field
    fun haAssets(info: JetStreamInfo) = info.haAssets

    @Field
    fun api(info: JetStreamInfo): JetStreamApiStats? = info.api

    @Field
    fun streams(info: JetStreamInfo) = info.streams

    @Field
    fun consumers(info: JetStreamInfo) = info.consumers

    @Field
    fun messages(info: JetStreamInfo) = info.messages

    @Field
    fun bytes(info: JetStreamInfo) = info.bytes
}
