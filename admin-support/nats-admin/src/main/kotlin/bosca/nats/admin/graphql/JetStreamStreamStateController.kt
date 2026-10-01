package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamStreamState

/**
 * Resolves fields on the JetStreamStreamState GraphQL type, exposing
 * runtime state of a JetStream stream including message counts and sequences.
 */
@TypeController
class JetStreamStreamStateController : GraphQLController<JetStreamStreamState> {

    @Field
    fun messages(state: JetStreamStreamState) = state.messages

    @Field
    fun bytes(state: JetStreamStreamState) = state.bytes

    @Field
    fun firstSeq(state: JetStreamStreamState) = state.firstSeq

    @Field
    fun lastSeq(state: JetStreamStreamState) = state.lastSeq

    @Field
    fun consumerCount(state: JetStreamStreamState) = state.consumerCount

    @Field
    fun numSubjects(state: JetStreamStreamState) = state.numSubjects

    @Field
    fun numDeleted(state: JetStreamStreamState) = state.numDeleted
}
