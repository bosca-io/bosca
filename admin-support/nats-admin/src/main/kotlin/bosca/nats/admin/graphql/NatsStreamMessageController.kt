package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.NatsHeader
import bosca.nats.admin.model.NatsStreamMessage

/**
 * Resolves fields on the NatsStreamMessage GraphQL type, exposing
 * individual messages from a NATS JetStream stream.
 */
@TypeController
class NatsStreamMessageController : GraphQLController<NatsStreamMessage> {

    @Field
    fun subject(msg: NatsStreamMessage) = msg.subject

    @Field
    fun sequence(msg: NatsStreamMessage) = msg.sequence

    @Field
    fun timestamp(msg: NatsStreamMessage) = msg.timestamp

    @Field
    fun data(msg: NatsStreamMessage) = msg.data

    @Field
    fun headers(msg: NatsStreamMessage): List<NatsHeader> = msg.headers
}
