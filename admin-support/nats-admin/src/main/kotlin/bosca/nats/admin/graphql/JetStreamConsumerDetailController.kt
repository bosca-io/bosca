package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamCluster
import bosca.nats.admin.model.JetStreamConsumerDetail
import bosca.nats.admin.model.JetStreamSequenceInfo

/**
 * Resolves fields on the JetStreamConsumerDetail GraphQL type, mapping
 * consumer state including delivery position and pending acknowledgments.
 */
@TypeController
class JetStreamConsumerDetailController : GraphQLController<JetStreamConsumerDetail> {

    @Field
    fun name(consumer: JetStreamConsumerDetail) = consumer.name

    @Field
    fun streamName(consumer: JetStreamConsumerDetail) = consumer.streamName

    @Field
    fun created(consumer: JetStreamConsumerDetail) = consumer.created

    @Field
    fun delivered(consumer: JetStreamConsumerDetail): JetStreamSequenceInfo? = consumer.delivered

    @Field
    fun ackFloor(consumer: JetStreamConsumerDetail): JetStreamSequenceInfo? = consumer.ackFloor

    @Field
    fun numAckPending(consumer: JetStreamConsumerDetail) = consumer.numAckPending

    @Field
    fun numRedelivered(consumer: JetStreamConsumerDetail) = consumer.numRedelivered

    @Field
    fun numWaiting(consumer: JetStreamConsumerDetail) = consumer.numWaiting

    @Field
    fun numPending(consumer: JetStreamConsumerDetail) = consumer.numPending

    @Field
    fun cluster(consumer: JetStreamConsumerDetail): JetStreamCluster? = consumer.cluster
}
