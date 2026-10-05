package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Detail for a single JetStream consumer, including delivery state,
 * pending acknowledgments, and redelivery counts.
 */
@Serializable
data class JetStreamConsumerDetail(
    val name: String,
    @SerialName("stream_name") val streamName: String,
    val created: String? = null,
    val delivered: JetStreamSequenceInfo? = null,
    @SerialName("ack_floor") val ackFloor: JetStreamSequenceInfo? = null,
    @SerialName("num_ack_pending") val numAckPending: Long = 0,
    @SerialName("num_redelivered") val numRedelivered: Long = 0,
    @SerialName("num_waiting") val numWaiting: Long = 0,
    @SerialName("num_pending") val numPending: Long = 0,
    val cluster: JetStreamCluster? = null,
)

/**
 * Sequence and timestamp for a consumer's delivery or acknowledgment position.
 */
@Serializable
data class JetStreamSequenceInfo(
    @SerialName("consumer_seq") val consumerSeq: Long = 0,
    @SerialName("stream_seq") val streamSeq: Long = 0,
)
