package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Overview statistics for the JetStream persistence layer from the `/jsz` monitoring endpoint.
 * Includes aggregate counts of streams, consumers, messages, and storage usage.
 */
@Serializable
data class JetStreamInfo(
    val memory: Long = 0,
    val storage: Long = 0,
    @SerialName("reserved_memory") val reservedMemory: Long = 0,
    @SerialName("reserved_storage") val reservedStorage: Long = 0,
    val accounts: Int = 0,
    @SerialName("ha_assets") val haAssets: Int = 0,
    val api: JetStreamApiStats? = null,
    val streams: Int = 0,
    val consumers: Int = 0,
    val messages: Long = 0,
    val bytes: Long = 0,
    @SerialName("account_details") val accountDetails: List<JetStreamAccountDetail>? = null,
)

/**
 * JetStream account-level detail containing stream and consumer breakdowns.
 */
@Serializable
data class JetStreamAccountDetail(
    val name: String,
    val id: String,
    val memory: Long = 0,
    val storage: Long = 0,
    @SerialName("stream_detail") val streamDetail: List<JetStreamStreamDetail>? = null,
)

/**
 * Detail for a single JetStream stream including its configuration state,
 * message counts, and storage usage.
 */
@Serializable
data class JetStreamStreamDetail(
    val name: String,
    val created: String? = null,
    val state: JetStreamStreamState? = null,
    val cluster: JetStreamCluster? = null,
)

/**
 * Runtime state of a JetStream stream: message count, byte usage, sequence numbers,
 * and consumer count.
 */
@Serializable
data class JetStreamStreamState(
    val messages: Long = 0,
    val bytes: Long = 0,
    @SerialName("first_seq") val firstSeq: Long = 0,
    @SerialName("last_seq") val lastSeq: Long = 0,
    @SerialName("consumer_count") val consumerCount: Int = 0,
    @SerialName("num_subjects") val numSubjects: Int = 0,
    @SerialName("num_deleted") val numDeleted: Int = 0,
)

/**
 * Cluster membership information for a JetStream stream or consumer.
 */
@Serializable
data class JetStreamCluster(
    val leader: String? = null,
)
