package bosca.gateway.service

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayRoute
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayConfigServiceImplTest {

    @BeforeTest
    fun setup() {
        service = GatewayConfigServiceImpl(
            gatewayRepository = gatewayRepository,
            routeRepository = routeRepository,
            configVersionRepository = configVersionRepository,
        )
    }

    private val gatewayRepository = mockk<GatewayRepository>()
    private val routeRepository = mockk<GatewayRouteRepository>()
    private val configVersionRepository = mockk<GatewayConfigVersionRepository>()

    private lateinit var service: GatewayConfigServiceImpl

    private val gatewayId = UUID.random()
    private val routeId = UUID.random()

    @Test
    fun `getVersion delegates to version repository`() = runTest {
        coEvery { configVersionRepository.getVersion() } returns "abc123"
        assertEquals("abc123", service.getVersion())
    }

    @Test
    fun `getConfig assembles full config from enabled rows only`() = runTest {
        val gateway = Gateway(id = gatewayId, name = "trino", url = "http://trino:8080")
        val route = GatewayRoute(
            id = routeId,
            gatewayId = gatewayId,
            pathPattern = "/trino/**",
            authMethod = GatewayAuthMethod.JWT,
            readGroups = listOf("analysts"),
        )

        coEvery { configVersionRepository.getVersion() } returns "v1"
        coEvery { gatewayRepository.getEnabled() } returns listOf(gateway)
        coEvery { routeRepository.getEnabled() } returns listOf(route)

        val config = service.getConfig()
        assertEquals("v1", config.version)
        assertEquals(1, config.services.size)
        assertEquals(1, config.routes.size)
    }

    @Test
    fun `getConfig returns empty payload when nothing is configured`() = runTest {
        coEvery { configVersionRepository.getVersion() } returns "v0"
        coEvery { gatewayRepository.getEnabled() } returns emptyList()
        coEvery { routeRepository.getEnabled() } returns emptyList()

        val config = service.getConfig()
        assertEquals("v0", config.version)
        assertEquals(0, config.services.size)
        assertEquals(0, config.routes.size)
    }
}
