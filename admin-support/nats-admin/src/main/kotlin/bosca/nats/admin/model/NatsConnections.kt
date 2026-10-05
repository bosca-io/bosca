package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Paginated response from the NATS `/connz` monitoring endpoint
 * containing active client connection details.
 */
@Serializable
data class NatsConnections(
    @SerialName("num_connections") val numConnections: Int,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val connections: List<NatsConnection> = emptyList(),
)
