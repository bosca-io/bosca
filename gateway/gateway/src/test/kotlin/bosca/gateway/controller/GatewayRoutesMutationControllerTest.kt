package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayRouteService
import bosca.gateway.service.GatewayService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reparenting a route from one gateway to another is the highest-
 * stakes mutation in the routing surface. The first review found
 * that an EDIT holder on the source gateway could move a route to
 * a different gateway they didn't have EDIT on; the tests below pin
 * that reparenting requires EDIT on BOTH source and target.
 *
 * Delete requires MANAGE (not EDIT) on the parent gateway — toggling
 * a route off is reversible (EDIT), but removing it changes the
 * gateway's published surface and should be a more deliberate
 * privilege level.
 */
class GatewayRoutesMutationControllerTest {

    @BeforeTest
    fun setup() {
        controller = GatewayRoutesMutationController(
            service = service,
            gatewayService = gatewayService,
            permissionEvaluator = permissionEvaluator,
        )
    }

    private val service = mockk<GatewayRouteService>()
    private val gatewayService = mockk<GatewayService>()
    private val permissionEvaluator = mockk<GatewayPermissionEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private lateinit var controller: GatewayRoutesMutationController

    private val sourceGatewayId = UUID.random()
    private val targetGatewayId = UUID.random()
    private val sourceGateway = Gateway(id = sourceGatewayId, name = "source", url = "http://src")
    private val targetGateway = Gateway(id = targetGatewayId, name = "target", url = "http://tgt")
    private val routeId = UUID.random()
    private val existingRoute = GatewayRoute(
        id = routeId,
        gatewayId = sourceGatewayId,
        pathPattern = "/api/**",
        authMethod = GatewayAuthMethod.JWT,
    )
    private val sameGatewayInput = GatewayRouteInput(
        gatewayId = sourceGatewayId,
        pathPattern = "/api/**",
        authMethod = GatewayAuthMethod.JWT,
    )
    private val reparentInput = GatewayRouteInput(
        gatewayId = targetGatewayId,
        pathPattern = "/api/**",
        authMethod = GatewayAuthMethod.JWT,
    )

    @Test
    fun `create requires EDIT on the input's gateway`() = runTest {
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.create(authentication, sameGatewayInput)
        }
        coVerify(exactly = 0) { service.create(any()) }
    }

    @Test
    fun `create throws NotFound when the input gateway does not exist`() = runTest {
        coEvery { gatewayService.getById(sourceGatewayId) } returns null

        assertFailsWith<GatewayNotFoundException> {
            controller.create(authentication, sameGatewayInput)
        }
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(any(), any(), any()) }
    }

    @Test
    fun `update without reparenting needs EDIT on the current parent only`() = runTest {
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } just Runs
        coEvery { service.update(routeId, sameGatewayInput, 0) } returns existingRoute

        controller.update(authentication, routeId, sameGatewayInput, 0)

        // No second permission check should be made when the gateway
        // doesn't change.
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(any(), any(), any()) }
    }

    @Test
    fun `update with reparenting requires EDIT on both source and target gateways`() = runTest {
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery { gatewayService.getById(targetGatewayId) } returns targetGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } just Runs
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, targetGateway, PermissionAction.EDIT)
        } just Runs
        coEvery { service.update(routeId, reparentInput, 0) } returns existingRoute

        controller.update(authentication, routeId, reparentInput, 0)

        coVerify {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        }
        coVerify {
            permissionEvaluator.verifyAllowed(authentication, targetGateway, PermissionAction.EDIT)
        }
    }

    @Test
    fun `update rejects reparenting when target gateway is not accessible`() = runTest {
        // Specifically: an EDIT holder on the source gateway cannot
        // move a route to a gateway they do not control.
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery { gatewayService.getById(targetGatewayId) } returns targetGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } just Runs
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, targetGateway, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.update(authentication, routeId, reparentInput, 0)
        }
        coVerify(exactly = 0) { service.update(any(), any(), any()) }
    }

    @Test
    fun `update throws NotFound when target gateway does not exist`() = runTest {
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery { gatewayService.getById(targetGatewayId) } returns null
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } just Runs

        assertFailsWith<GatewayNotFoundException> {
            controller.update(authentication, routeId, reparentInput, 0)
        }
    }

    @Test
    fun `delete requires MANAGE not EDIT`() = runTest {
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.MANAGE)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.delete(authentication, routeId, 0)
        }
        coVerify(exactly = 0) { service.delete(any(), any()) }
    }

    @Test
    fun `toggleEnabled is an EDIT-level mutation`() = runTest {
        coEvery { service.getById(routeId) } returns existingRoute
        coEvery { gatewayService.getById(sourceGatewayId) } returns sourceGateway
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, sourceGateway, PermissionAction.EDIT)
        } just Runs
        coEvery { service.toggleEnabled(routeId, false, 0) } returns existingRoute.copy(enabled = false)

        val result = controller.toggleEnabled(authentication, routeId, false, 0)

        assertEquals(false, result.enabled)
    }
}
