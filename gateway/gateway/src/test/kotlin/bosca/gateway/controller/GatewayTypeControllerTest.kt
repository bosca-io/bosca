package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayPermission
import bosca.gateway.model.GatewayRoute
import bosca.gateway.service.GatewayPermissionEvaluator
import bosca.gateway.service.GatewayRouteService
import bosca.gateway.service.GatewayService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Field-level permission gating on the [Gateway] GraphQL type.
 *
 * `routes` is intentionally public-by-visibility — any caller who can
 * VIEW the gateway can list its routes (the route metadata itself is
 * filtered separately by [GatewayRoutesQueryController]).
 *
 * `permissions` reveals the per-gateway ACL (group UUIDs of everyone
 * with administrative access), so it's gated on MANAGE. Without MANAGE
 * the field silently returns `[]` — that's an intentional masking
 * decision so a caller with only VIEW can still hydrate a Gateway
 * object without GraphQL errors, while not learning who else has
 * access.
 */
class GatewayTypeControllerTest {

    @BeforeTest
    fun setup() {
        controller = GatewayTypeController(
            routeService = routeService,
            gatewayService = gatewayService,
            permissionEvaluator = permissionEvaluator,
        )
    }

    private val routeService = mockk<GatewayRouteService>()
    private val gatewayService = mockk<GatewayService>()
    private val permissionEvaluator = mockk<GatewayPermissionEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private lateinit var controller: GatewayTypeController

    private val gatewayId = UUID.random()
    private val gateway = Gateway(id = gatewayId, name = "g", url = "http://g")

    private fun route() = GatewayRoute(
        id = UUID.random(),
        gatewayId = gatewayId,
        pathPattern = "/p",
        authMethod = GatewayAuthMethod.JWT,
    )

    private fun permission() = GatewayPermission(
        gatewayId = gatewayId,
        groupId = UUID.random(),
        action = PermissionAction.VIEW,
        grantedBy = UUID.random(),
    )

    @Test
    fun `routes is not gated on MANAGE`() = runTest {
        val routes = listOf(route(), route())
        coEvery { routeService.listByGatewayId(gatewayId) } returns routes

        assertEquals(routes, controller.routes(gateway))

        // The routes field deliberately does NOT consult the
        // permission evaluator — a VIEW-only caller can list the
        // route surface, while individual routes are still gated
        // by GatewayRoutesQueryController.
        coVerify(exactly = 0) {
            permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Gateway>(), any<PermissionAction>())
        }
    }

    @Test
    fun `permissions returns empty when MANAGE is denied`() = runTest {
        coEvery {
            permissionEvaluator.isAllowed(authentication, gateway, PermissionAction.MANAGE)
        } returns false

        assertEquals(emptyList(), controller.permissions(authentication, gateway))
        coVerify(exactly = 0) { gatewayService.getPermissions(any()) }
    }

    @Test
    fun `permissions returns rows when MANAGE is allowed`() = runTest {
        coEvery {
            permissionEvaluator.isAllowed(authentication, gateway, PermissionAction.MANAGE)
        } returns true
        val permissions = listOf(permission(), permission())
        coEvery { gatewayService.getPermissions(gateway) } returns permissions

        assertEquals(permissions, controller.permissions(authentication, gateway))
    }

    @Test
    fun `scalar field resolvers return their values`() = runTest {
        // Cheap coverage on the trivial accessors — guards against a
        // refactor accidentally returning the wrong field.
        assertEquals(gatewayId, controller.id(gateway))
        assertEquals("g", controller.name(gateway))
        assertEquals("http://g", controller.url(gateway))
        assertEquals(true, controller.enabled(gateway))
        assertEquals(0L, controller.version(gateway))
    }
}
