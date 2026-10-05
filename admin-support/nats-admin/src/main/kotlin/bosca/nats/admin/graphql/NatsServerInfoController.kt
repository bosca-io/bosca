package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamServerConfig
import bosca.nats.admin.model.NatsServerInfo

/**
 * Resolves fields on the NatsServerInfo GraphQL type, mapping from
 * the NATS `/varz` monitoring response to the GraphQL schema.
 */
@TypeController
class NatsServerInfoController : GraphQLController<NatsServerInfo> {

    @Field
    fun serverId(info: NatsServerInfo) = info.serverId

    @Field
    fun serverName(info: NatsServerInfo) = info.serverName

    @Field
    fun version(info: NatsServerInfo) = info.version

    @Field
    fun host(info: NatsServerInfo) = info.host

    @Field
    fun port(info: NatsServerInfo) = info.port

    @Field
    fun maxConnections(info: NatsServerInfo) = info.maxConnections

    @Field
    fun maxPayload(info: NatsServerInfo) = info.maxPayload

    @Field
    fun uptime(info: NatsServerInfo) = info.uptime

    @Field
    fun mem(info: NatsServerInfo) = info.mem

    @Field
    fun cpu(info: NatsServerInfo) = info.cpu

    @Field
    fun connections(info: NatsServerInfo) = info.connections

    @Field
    fun totalConnections(info: NatsServerInfo) = info.totalConnections

    @Field
    fun subscriptions(info: NatsServerInfo) = info.subscriptions

    @Field
    fun slowConsumers(info: NatsServerInfo) = info.slowConsumers

    @Field
    fun inMsgs(info: NatsServerInfo) = info.inMsgs

    @Field
    fun outMsgs(info: NatsServerInfo) = info.outMsgs

    @Field
    fun inBytes(info: NatsServerInfo) = info.inBytes

    @Field
    fun outBytes(info: NatsServerInfo) = info.outBytes

    @Field
    fun jetstream(info: NatsServerInfo): JetStreamServerConfig? = info.jetstream
}
