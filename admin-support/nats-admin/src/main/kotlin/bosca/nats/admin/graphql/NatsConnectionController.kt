package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsConnection

/**
 * Resolves fields on the NatsConnection GraphQL type, mapping
 * individual connection details from the NATS `/connz` response.
 */
@TypeController
class NatsConnectionController : GraphQLController<NatsConnection> {

    @Field
    fun cid(conn: NatsConnection) = conn.cid

    @Field
    fun kind(conn: NatsConnection) = conn.kind

    @Field
    fun type(conn: NatsConnection) = conn.type

    @Field
    fun ip(conn: NatsConnection) = conn.ip

    @Field
    fun port(conn: NatsConnection) = conn.port

    @Field
    fun start(conn: NatsConnection) = conn.start

    @Field
    fun lastActivity(conn: NatsConnection) = conn.lastActivity

    @Field
    fun rtt(conn: NatsConnection) = conn.rtt

    @Field
    fun uptime(conn: NatsConnection) = conn.uptime

    @Field
    fun idle(conn: NatsConnection) = conn.idle

    @Field
    fun pendingBytes(conn: NatsConnection) = conn.pendingBytes

    @Field
    fun inMsgs(conn: NatsConnection) = conn.inMsgs

    @Field
    fun outMsgs(conn: NatsConnection) = conn.outMsgs

    @Field
    fun inBytes(conn: NatsConnection) = conn.inBytes

    @Field
    fun outBytes(conn: NatsConnection) = conn.outBytes

    @Field
    fun subscriptions(conn: NatsConnection) = conn.subscriptions

    @Field
    fun name(conn: NatsConnection) = conn.name

    @Field
    fun lang(conn: NatsConnection) = conn.lang

    @Field
    fun version(conn: NatsConnection) = conn.version
}
