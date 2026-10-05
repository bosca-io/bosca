package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayGroups
import bosca.gateway.model.GatewayInput
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.repository.GatewayPermissionRepository
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Security regression coverage for [GatewayServicesMutationController].
 *
 * These tests pin two non-obvious rules that the code review surfaced:
 *
 * 1. `create` requires [GatewayGroups.ADMIN] — not just per-entity
 *    EDIT — because creating a Gateway lets the caller register an
 *    arbitrary upstream URL.
 * 2. `update` requires [GatewayGroups.ADMIN] **in addition to**
 *    per-entity EDIT when the request changes `url` or `name`,
 *    because those fields can repoint traffic to an attacker-controlled
 *    upstream.
 */
class GatewayServicesMutationControllerTest {

    @BeforeTest
    fun setup() {
        controller = GatewayServicesMutationController(
            service = service,
            permissionEvaluator = permissionEvaluator,
            permissionRepository = permissionRepository,
            groupEvaluator = groupEvaluator,
        )
    }

    private val service = mockk<GatewayService>()
    private val permissionEvaluator = mockk<GatewayPermissionEvaluator>()
    private val permissionRepository = mockk<GatewayPermissionRepository>(relaxUnitFun = true)
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private lateinit var controller: GatewayServicesMutationController

    private val gatewayId = UUID.random()
    private val sampleGateway = Gateway(
        id = gatewayId,
        name = "trino",
        url = "http://trino.internal:8080",
    )
    private val sampleInput = GatewayInput(
        name = "trino",
        url = "http://trino.internal:8080",
    )

    @Test
    fun `create requires gateway-admin group`() = runTest {
        coEvery { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) } just Runs
        coEvery { service.create(sampleInput) } returns sampleGateway

        controller.create(authentication, sampleInput)

        coVerify { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) }
        coVerify { service.create(sampleInput) }
    }

    @Test
    fun `create propagates verifyHasGroup failure without calling service`() = runTest {
        coEvery {
            groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> { controller.create(authentication, sampleInput) }
        coVerify(exactly = 0) { service.create(any()) }
    }

    @Test
    fun `update without url or name change requires only per-entity EDIT`() = runTest {
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.EDIT)
        } just Runs
        // Same name and URL — only the timeouts change.
        val sameUrlInput = sampleInput.copy(connectTimeoutSecs = 99)
        coEvery { service.update(gatewayId, sameUrlInput, 0) } returns sampleGateway

        controller.update(authentication, gatewayId, sameUrlInput, 0)

        // gateway-admin must NOT be required when url and name are unchanged.
        coVerify(exactly = 0) { groupEvaluator.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `update with url change requires gateway-admin in addition to EDIT`() = runTest {
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.EDIT)
        } just Runs
        coEvery { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) } just Runs
        val repointed = sampleInput.copy(url = "http://attacker.example.com")
        coEvery { service.update(gatewayId, repointed, 0) } returns sampleGateway

        controller.update(authentication, gatewayId, repointed, 0)

        coVerify { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) }
    }

    @Test
    fun `update with name change requires gateway-admin in addition to EDIT`() = runTest {
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.EDIT)
        } just Runs
        coEvery { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) } just Runs
        val renamed = sampleInput.copy(name = "stolen")
        coEvery { service.update(gatewayId, renamed, 0) } returns sampleGateway

        controller.update(authentication, gatewayId, renamed, 0)

        coVerify { groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN) }
    }

    @Test
    fun `update propagates verifyHasGroup denial after url change attempt`() = runTest {
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.EDIT)
        } just Runs
        coEvery {
            groupEvaluator.verifyHasGroup(authentication, GatewayGroups.ADMIN)
        } throws SecurityException("denied")
        val repointed = sampleInput.copy(url = "http://attacker.example.com")

        assertFailsWith<SecurityException> {
            controller.update(authentication, gatewayId, repointed, 0)
        }
        coVerify(exactly = 0) { service.update(any(), any(), any()) }
    }

    @Test
    fun `update on missing gateway throws typed NotFound`() = runTest {
        coEvery { service.getById(gatewayId) } returns null

        assertFailsWith<GatewayNotFoundException> {
            controller.update(authentication, gatewayId, sampleInput, 0)
        }
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(any(), any(), any()) }
        coVerify(exactly = 0) { groupEvaluator.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `addPermission captures granted-by from authentication principal`() = runTest {
        val principalId = UUID.random()
        val principal = AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { authentication.principal() } returns principal
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.MANAGE)
        } just Runs
        val groupId = UUID.random()

        assertTrue(controller.addPermission(authentication, gatewayId, groupId, PermissionAction.VIEW))

        coVerify {
            permissionRepository.add(gatewayId, groupId, PermissionAction.VIEW, principalId)
        }
    }

    @Test
    fun `addPermission requires MANAGE not EDIT (no privilege escalation from EDIT)`() = runTest {
        coEvery { service.getById(gatewayId) } returns sampleGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sampleGateway, PermissionAction.MANAGE)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.addPermission(authentication, gatewayId, UUID.random(), PermissionAction.MANAGE)
        }
        coVerify(exactly = 0) { permissionRepository.add(any(), any(), any(), any()) }
    }
}
