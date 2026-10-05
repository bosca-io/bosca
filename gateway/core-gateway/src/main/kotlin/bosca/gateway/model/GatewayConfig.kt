package bosca.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    val version: String,
    val services: List<Gateway>,
    val routes: List<GatewayRoute>,
)
