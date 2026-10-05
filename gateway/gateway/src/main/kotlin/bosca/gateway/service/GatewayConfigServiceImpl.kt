package bosca.gateway.service

import bosca.gateway.model.GatewayConfig
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class GatewayConfigServiceImpl(
    private val gatewayRepository: GatewayRepository,
    private val routeRepository: GatewayRouteRepository,
    private val configVersionRepository: GatewayConfigVersionRepository,
) : GatewayConfigService {

    override suspend fun getVersion(): String = configVersionRepository.getVersion()

    override suspend fun getConfig(): GatewayConfig = GatewayConfig(
        version = configVersionRepository.getVersion(),
        services = gatewayRepository.getEnabled(),
        routes = routeRepository.getEnabled(),
    )
}
