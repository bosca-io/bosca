package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Details about a single active client connection to the NATS server,
 * as reported by the `/connz` monitoring endpoint.
 */
@Serializable
data class NatsConnection(
    val cid: Long,
    val kind: String? = null,
    val type: String? = null,
    val ip: String,
    val port: Int,
    val start: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null,
    val rtt: String? = null,
    val uptime: String,
    val idle: String,
    @SerialName("pending_bytes") val pendingBytes: Long = 0,
    @SerialName("in_msgs") val inMsgs: Long = 0,
    @SerialName("out_msgs") val outMsgs: Long = 0,
    @SerialName("in_bytes") val inBytes: Long = 0,
    @SerialName("out_bytes") val outBytes: Long = 0,
    val subscriptions: Int = 0,
    val name: String? = null,
    val lang: String? = null,
    val version: String? = null,
)
