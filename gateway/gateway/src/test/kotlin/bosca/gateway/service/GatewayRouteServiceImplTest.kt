package bosca.gateway.service

import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayOptimisticLockFailedException
import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayRouteServiceImplTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = GatewayRouteServiceImpl(
            repository = repository,
            configVersionRepository = configVersionRepository,
        )
    }

    private val repository = mockk<GatewayRouteRepository>()
    private val configVersionRepository = mockk<GatewayConfigVersionRepository>()

    private lateinit var service: GatewayRouteServiceImpl

    private val gatewayId = UUID.random()
    private val routeId = UUID.random()

    private val sampleRoute = GatewayRoute(
        id = routeId,
        gatewayId = gatewayId,
        pathPattern = "/trino/**",
        authMethod = GatewayAuthMethod.OAUTH2,
        stripPrefix = true,
        readGroups = listOf("analysts"),
        writeGroups = listOf("trino-admins"),
        injectHeaders = JsonObject(mapOf("X-Trino-User" to JsonPrimitive("{{user.email}}"))),
    )

    private val sampleInput = GatewayRouteInput(
        gatewayId = gatewayId,
        pathPattern = "/trino/**",
        authMethod = GatewayAuthMethod.OAUTH2,
        stripPrefix = true,
        readGroups = listOf("analysts"),
        writeGroups = listOf("trino-admins"),
        injectHeaders = JsonObject(mapOf("X-Trino-User" to JsonPrimitive("{{user.email}}"))),
    )

    @Test
    fun `listAll returns all routes`() = runTest {
        coEvery { repository.getAll() } returns listOf(sampleRoute)
        assertEquals(1, service.listAll().size)
    }

    @Test
    fun `listByGatewayId delegates to repository`() = runTest {
        coEvery { repository.getByGatewayId(gatewayId) } returns listOf(sampleRoute)
        assertEquals(1, service.listByGatewayId(gatewayId).size)
    }

    @Test
    fun `create persists route and bumps version`() = runTest {
        coEvery { repository.add(any()) } returns sampleRoute
        coEvery { configVersionRepository.bump() } returns "v2"

        val result = service.create(sampleInput)
        assertEquals("/trino/**", result.pathPattern)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `update throws OptimisticLockFailed when row exists but version mismatched`() = runTest {
        coEvery {
            repository.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { repository.getById(routeId) } returns sampleRoute

        assertFailsWith<GatewayOptimisticLockFailedException> {
            service.update(routeId, sampleInput, 99)
        }
    }

    @Test
    fun `update throws NotFound when row is missing`() = runTest {
        coEvery {
            repository.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { repository.getById(routeId) } returns null

        assertFailsWith<GatewayNotFoundException> { service.update(routeId, sampleInput, 0) }
    }

    @Test
    fun `create rejects a malformed host pattern`() = runTest {
        // An operator typo (e.g. embedded space) must be rejected at
        // the input boundary — otherwise the row gets persisted and
        // silently fails to match any inbound request at the proxy.
        val badInput = sampleInput.copy(hosts = listOf("not a host"))
        assertFailsWith<IllegalArgumentException> { service.create(badInput) }
    }

    @Test
    fun `create accepts a wildcard host`() = runTest {
        coEvery { repository.add(any()) } returns sampleRoute
        coEvery { configVersionRepository.bump() } returns "v2"

        val input = sampleInput.copy(hosts = listOf("*.bosca.io"))
        val result = service.create(input)
        assertEquals("/trino/**", result.pathPattern)
    }

    @Test
    fun `delete bumps version on success`() = runTest {
        coEvery { repository.delete(routeId, 0) } returns sampleRoute
        coEvery { configVersionRepository.bump() } returns "v3"

        service.delete(routeId, 0)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `countByGatewayId delegates to repository`() = runTest {
        coEvery { repository.countByGatewayId(gatewayId) } returns 5
        assertEquals(5, service.countByGatewayId(gatewayId))
    }
}
