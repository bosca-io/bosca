package bosca.gateway.service

import bosca.gateway.model.GatewayConfig
import bosca.service.Service

interface GatewayConfigService : Service {
    suspend fun getVersion(): String
    suspend fun getConfig(): GatewayConfig
}
