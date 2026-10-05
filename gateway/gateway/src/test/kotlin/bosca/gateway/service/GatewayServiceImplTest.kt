package bosca.gateway.service

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayConflictException
import bosca.gateway.model.GatewayInUseException
import bosca.gateway.model.GatewayInput
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayOptimisticLockFailedException
import bosca.gateway.model.GatewayPermission
import bosca.gateway.repository.GatewayConfigVersionRepository
import bosca.gateway.repository.GatewayPermissionRepository
import bosca.gateway.repository.GatewayRepository
import bosca.gateway.repository.GatewayRouteRepository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GatewayServiceImplTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = GatewayServiceImpl(
            repository = repository,
            routeRepository = routeRepository,
            configVersionRepository = configVersionRepository,
            permissionRepository = permissionRepository,
        )
    }

    private val repository = mockk<GatewayRepository>()
    private val routeRepository = mockk<GatewayRouteRepository>()
    private val configVersionRepository = mockk<GatewayConfigVersionRepository>()
    private val permissionRepository = mockk<GatewayPermissionRepository>()

    private lateinit var service: GatewayServiceImpl

    private val gatewayId = UUID.random()

    private val sampleGateway = Gateway(
        id = gatewayId,
        name = "trino",
        url = "http://trino.internal:8080",
        healthCheckPath = "/v1/info",
    )

    private val sampleInput = GatewayInput(
        name = "trino",
        url = "http://trino.internal:8080",
        healthCheckPath = "/v1/info",
    )

    @Test
    fun `listAll returns all gateways`() = runTest {
        coEvery { repository.getAll() } returns listOf(sampleGateway)
        val result = service.listAll()
        assertEquals(1, result.size)
        assertEquals("trino", result[0].name)
    }

    @Test
    fun `getById returns gateway when found`() = runTest {
        coEvery { repository.getById(gatewayId) } returns sampleGateway
        assertNotNull(service.getById(gatewayId))
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        coEvery { repository.getById(gatewayId) } returns null
        assertNull(service.getById(gatewayId))
    }

    @Test
    fun `create bumps config version and persists gateway`() = runTest {
        coEvery { repository.getByName("trino") } returns null
        coEvery { repository.add(any()) } returns sampleGateway
        coEvery { configVersionRepository.bump() } returns "v2"

        val result = service.create(sampleInput)
        assertEquals("trino", result.name)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `create rejects duplicate names with typed conflict exception`() = runTest {
        coEvery { repository.getByName("trino") } returns sampleGateway

        assertFailsWith<GatewayConflictException> { service.create(sampleInput) }
        coVerify(exactly = 0) { configVersionRepository.bump() }
    }

    @Test
    fun `update succeeds with correct version`() = runTest {
        coEvery { repository.getByName("trino") } returns sampleGateway
        coEvery {
            repository.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns sampleGateway
        coEvery { configVersionRepository.bump() } returns "v3"

        val result = service.update(gatewayId, sampleInput, 0)
        assertEquals("trino", result.name)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `update fails on name conflict with different id`() = runTest {
        val otherGateway = sampleGateway.copy(id = UUID.random())
        coEvery { repository.getByName("trino") } returns otherGateway

        assertFailsWith<GatewayConflictException> { service.update(gatewayId, sampleInput, 0) }
    }

    @Test
    fun `update throws OptimisticLockFailed when version mismatched but row exists`() = runTest {
        coEvery { repository.getByName("trino") } returns sampleGateway
        coEvery {
            repository.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { repository.getById(gatewayId) } returns sampleGateway

        assertFailsWith<GatewayOptimisticLockFailedException> {
            service.update(gatewayId, sampleInput, 99)
        }
    }

    @Test
    fun `update throws NotFound when row is missing`() = runTest {
        coEvery { repository.getByName("trino") } returns null
        coEvery {
            repository.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { repository.getById(gatewayId) } returns null

        assertFailsWith<GatewayNotFoundException> { service.update(gatewayId, sampleInput, 0) }
    }

    @Test
    fun `delete fails with typed in-use exception when routes exist`() = runTest {
        coEvery { routeRepository.countByGatewayId(gatewayId) } returns 3

        assertFailsWith<GatewayInUseException> { service.delete(gatewayId, 0) }
        coVerify(exactly = 0) { configVersionRepository.bump() }
    }

    @Test
    fun `delete bumps config version when no routes reference the gateway`() = runTest {
        coEvery { routeRepository.countByGatewayId(gatewayId) } returns 0
        coEvery { repository.delete(gatewayId, 0) } returns sampleGateway
        coEvery { configVersionRepository.bump() } returns "v4"

        val result = service.delete(gatewayId, 0)
        assertEquals("trino", result.name)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `toggleEnabled bumps version on success`() = runTest {
        val disabled = sampleGateway.copy(enabled = false)
        coEvery { repository.toggleEnabled(gatewayId, false, 0) } returns disabled
        coEvery { configVersionRepository.bump() } returns "v5"

        assertEquals(false, service.toggleEnabled(gatewayId, false, 0).enabled)
        coVerify { configVersionRepository.bump() }
    }

    @Test
    fun `toggleEnabled throws OptimisticLockFailed when version mismatched`() = runTest {
        coEvery { repository.toggleEnabled(gatewayId, true, 99) } returns null
        coEvery { repository.getById(gatewayId) } returns sampleGateway

        assertFailsWith<GatewayOptimisticLockFailedException> {
            service.toggleEnabled(gatewayId, true, 99)
        }
    }

    @Test
    fun `getPermissions delegates to permission repository`() = runTest {
        val rows = listOf(
            GatewayPermission(
                gatewayId = gatewayId,
                groupId = UUID.random(),
                action = PermissionAction.VIEW,
                grantedBy = UUID.random(),
            ),
        )
        coEvery { permissionRepository.getByGatewayId(gatewayId) } returns rows
        assertEquals(1, service.getPermissions(sampleGateway).size)
    }
}
