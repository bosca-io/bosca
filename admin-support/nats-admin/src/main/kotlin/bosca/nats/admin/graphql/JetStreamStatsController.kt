package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamApiStats
import bosca.nats.admin.model.JetStreamStats

/**
 * Resolves fields on the JetStreamStats GraphQL type, exposing
 * runtime resource usage and API statistics for JetStream.
 */
@TypeController
class JetStreamStatsController : GraphQLController<JetStreamStats> {

    @Field
    fun memory(stats: JetStreamStats) = stats.memory

    @Field
    fun storage(stats: JetStreamStats) = stats.storage

    @Field
    fun reservedMemory(stats: JetStreamStats) = stats.reservedMemory

    @Field
    fun reservedStorage(stats: JetStreamStats) = stats.reservedStorage

    @Field
    fun accounts(stats: JetStreamStats) = stats.accounts

    @Field
    fun haAssets(stats: JetStreamStats) = stats.haAssets

    @Field
    fun api(stats: JetStreamStats): JetStreamApiStats? = stats.api
}
