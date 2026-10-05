package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsConnection
import bosca.nats.admin.model.NatsConnections

/**
 * Resolves fields on the NatsConnections GraphQL type, the paginated
 * wrapper for connection lists returned by the NATS `/connz` endpoint.
 */
@TypeController
class NatsConnectionsController : GraphQLController<NatsConnections> {

    @Field
    fun numConnections(conns: NatsConnections) = conns.numConnections

    @Field
    fun total(conns: NatsConnections) = conns.total

    @Field
    fun offset(conns: NatsConnections) = conns.offset

    @Field
    fun limit(conns: NatsConnections) = conns.limit

    @Field
    fun connections(conns: NatsConnections): List<NatsConnection> = conns.connections
}
