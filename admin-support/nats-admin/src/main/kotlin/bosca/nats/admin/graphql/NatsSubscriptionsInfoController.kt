package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsSubscriptionsInfo

/**
 * Resolves fields on the NatsSubscriptionsInfo GraphQL type, mapping
 * subscription routing statistics from the NATS `/subsz` endpoint.
 */
@TypeController
class NatsSubscriptionsInfoController : GraphQLController<NatsSubscriptionsInfo> {

    @Field
    fun numSubscriptions(info: NatsSubscriptionsInfo) = info.numSubscriptions

    @Field
    fun numCache(info: NatsSubscriptionsInfo) = info.numCache

    @Field
    fun numInserts(info: NatsSubscriptionsInfo) = info.numInserts

    @Field
    fun numRemoves(info: NatsSubscriptionsInfo) = info.numRemoves

    @Field
    fun numMatches(info: NatsSubscriptionsInfo) = info.numMatches

    @Field
    fun cacheHitRate(info: NatsSubscriptionsInfo) = info.cacheHitRate

    @Field
    fun maxFanout(info: NatsSubscriptionsInfo) = info.maxFanout

    @Field
    fun avgFanout(info: NatsSubscriptionsInfo) = info.avgFanout
}
