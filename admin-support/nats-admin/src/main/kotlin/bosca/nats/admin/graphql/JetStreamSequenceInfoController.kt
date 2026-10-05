package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamSequenceInfo

/**
 * Resolves fields on the JetStreamSequenceInfo GraphQL type, exposing
 * consumer and stream sequence positions for delivery tracking.
 */
@TypeController
class JetStreamSequenceInfoController : GraphQLController<JetStreamSequenceInfo> {

    @Field
    fun consumerSeq(info: JetStreamSequenceInfo) = info.consumerSeq

    @Field
    fun streamSeq(info: JetStreamSequenceInfo) = info.streamSeq
}
