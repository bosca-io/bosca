package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-level statistics and configuration from the NATS `/varz` monitoring endpoint.
 * Provides an overview of the server's identity, resource usage, and message throughput.
 */
@Serializable
data class NatsServerInfo(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_name") val serverName: String,
    val version: String,
    val host: String,
    val port: Int,
    @SerialName("max_connections") val maxConnections: Int,
    @SerialName("max_payload") val maxPayload: Int,
    val uptime: String,
    val mem: Long,
    val cpu: Double,
    val connections: Int,
    @SerialName("total_connections") val totalConnections: Long,
    val subscriptions: Long,
    @SerialName("slow_consumers") val slowConsumers: Long,
    @SerialName("in_msgs") val inMsgs: Long,
    @SerialName("out_msgs") val outMsgs: Long,
    @SerialName("in_bytes") val inBytes: Long,
    @SerialName("out_bytes") val outBytes: Long,
    val jetstream: JetStreamServerConfig? = null,
)

/**
 * JetStream configuration and runtime statistics embedded within the server info response.
 */
@Serializable
data class JetStreamServerConfig(
    val config: JetStreamConfig? = null,
    val stats: JetStreamStats? = null,
)

/**
 * JetStream storage configuration limits.
 */
@Serializable
data class JetStreamConfig(
    @SerialName("max_memory") val maxMemory: Long = 0,
    @SerialName("max_storage") val maxStorage: Long = 0,
    @SerialName("store_dir") val storeDir: String = "",
)

/**
 * JetStream runtime resource usage and API call statistics.
 */
@Serializable
data class JetStreamStats(
    val memory: Long = 0,
    val storage: Long = 0,
    @SerialName("reserved_memory") val reservedMemory: Long = 0,
    @SerialName("reserved_storage") val reservedStorage: Long = 0,
    val accounts: Int = 0,
    @SerialName("ha_assets") val haAssets: Int = 0,
    val api: JetStreamApiStats? = null,
)

/**
 * JetStream API call counts and error totals.
 */
@Serializable
data class JetStreamApiStats(
    val total: Long = 0,
    val errors: Long = 0,
)
