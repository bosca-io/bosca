package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from the NATS `/routez` monitoring endpoint containing information
 * about active route connections between servers in a NATS cluster.
 */
@Serializable
data class NatsRoutes(
    @SerialName("num_routes") val numRoutes: Int,
    val routes: List<NatsRoute> = emptyList(),
)

/**
 * Details about a single route connection between two servers in a NATS cluster,
 * including the remote server identity, connection direction, and message throughput.
 */
@Serializable
data class NatsRoute(
    val rid: Long,
    @SerialName("remote_id") val remoteId: String = "",
    @SerialName("remote_name") val remoteName: String = "",
    @SerialName("did_solicit") val didSolicit: Boolean = false,
    @SerialName("is_configured") val isConfigured: Boolean = false,
    val ip: String = "",
    val port: Int = 0,
    @SerialName("pending_size") val pendingSize: Long = 0,
    @SerialName("in_msgs") val inMsgs: Long = 0,
    @SerialName("out_msgs") val outMsgs: Long = 0,
    @SerialName("in_bytes") val inBytes: Long = 0,
    @SerialName("out_bytes") val outBytes: Long = 0,
    val subscriptions: Int = 0,
)
