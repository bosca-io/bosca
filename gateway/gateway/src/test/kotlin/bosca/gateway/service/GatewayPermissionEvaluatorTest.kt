package bosca.gateway.service

import bosca.gateway.controller.GatewayRoutesMutationController
import bosca.gateway.controller.GatewayRoutesQueryController
import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayPermission
import bosca.gateway.model.GatewayRoute
import bosca.gateway.model.GatewayRouteInput
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayPermissionEvaluatorTest {
    private val security = mockk<SecurityService>()
    private val service = mockk<GatewayService>()
    private val routes = mockk<GatewayRouteService>()
    private val evaluator = GatewayPermissionEvaluator(service, security, GroupEvaluator(security))
    private val administrator = Group(UUID.random(), "administrators", "", GroupType.PRINCIPAL)
    private val team = administrator.copy(id = UUID.random(), name = "gateway-operators")
    private val gateway = Gateway(id = UUID.random(), name = "internal", url = "http://internal.example")
    private val route = GatewayRoute(id = UUID.random(), gatewayId = gateway.id, pathPattern = "/api/**", authMethod = GatewayAuthMethod.JWT)
    private val input = GatewayRouteInput(gatewayId = gateway.id, pathPattern = route.pathPattern, authMethod = GatewayAuthMethod.NONE)
    private var permissions: List<EntityPermission> = emptyList()

    @BeforeTest
    fun setup() {
        coEvery { service.getPermissions(any()) } answers { permissions }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
        coEvery { service.getById(gateway.id) } returns gateway
        coEvery { routes.getById(route.id) } returns route
        coEvery { routes.listByGatewayId(gateway.id) } returns listOf(route)
        coEvery { routes.create(input) } returns route.copy(authMethod = input.authMethod)
        coEvery { routes.update(route.id, input, route.version) } returns route.copy(authMethod = input.authMethod)
        coEvery { routes.toggleEnabled(route.id, false, route.version) } returns route.copy(enabled = false)
    }

    private fun authentication(scopes: List<String>?, group: Group = administrator): AuthenticationContext =
        context(ScopedAuthenticatedPrincipal(Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1L))

    private fun context(principal: AuthenticatedPrincipal): AuthenticationContext = mockk {
        every { principal() } returns principal
    }

    @Test
    fun `proxy write tokens cannot create unauthenticated routes or change existing routes`() = runTest {
        val authentication = authentication(listOf(ApiTokenScopes.GATEWAY_WRITE.name))
        val controller = GatewayRoutesMutationController(routes, service, evaluator)
        assertFailsWith<SecurityException> { controller.create(authentication, input) }
        assertFailsWith<SecurityException> { controller.update(authentication, route.id, input, route.version) }
        assertFailsWith<SecurityException> { controller.toggleEnabled(authentication, route.id, false, route.version) }
        coVerify(exactly = 0) { routes.create(any()) }
        coVerify(exactly = 0) { routes.update(any(), any(), any()) }
        coVerify(exactly = 0) { routes.toggleEnabled(any(), any(), any()) }
    }

    @Test
    fun `proxy read tokens cannot inspect private gateway route configuration`() = runTest {
        val authentication = authentication(listOf(ApiTokenScopes.GATEWAY_READ.name))
        val controller = GatewayRoutesQueryController(routes, service, evaluator)
        assertNull(controller.route(authentication, route.id))
        assertFailsWith<SecurityException> { controller.byGateway(authentication, gateway.id) }
        coVerify(exactly = 0) { routes.listByGatewayId(any()) }
    }

    @Test
    fun `other scopes cannot grant private gateway access through built in roles`() = runTest {
        val scopes = ApiTokenScopes.all.filter { it != ApiTokenScopes.SECURITY_MANAGE }.map { it.name }
        for (role in listOf("administrators", "sa", "editors", "managers")) {
            val authentication = authentication(scopes, administrator.copy(name = role))
            for (action in PermissionAction.entries) {
                assertFalse(evaluator.isAllowed(authentication, gateway, action), "$role $action")
            }
        }
    }

    @Test
    fun `entity grants still require management scope and scope alone does not grant access`() = runTest {
        val operator = authentication(listOf(ApiTokenScopes.SECURITY_MANAGE.name), team)
        assertFalse(evaluator.isAllowed(operator, gateway, PermissionAction.EDIT))
        permissions = listOf(GatewayPermission(gateway.id, team.id, PermissionAction.EDIT, UUID.random()))
        assertFalse(evaluator.isAllowed(authentication(listOf(ApiTokenScopes.GATEWAY_WRITE.name), team), gateway, PermissionAction.EDIT))
        assertTrue(evaluator.isAllowed(operator, gateway, PermissionAction.EDIT))
        assertFalse(evaluator.isAllowed(operator, gateway, PermissionAction.MANAGE))
    }

    @Test
    fun `management scope retains authorized route administration`() = runTest {
        val authentication = authentication(listOf(ApiTokenScopes.SECURITY_MANAGE.name))
        val controller = GatewayRoutesMutationController(routes, service, evaluator)
        assertEquals(GatewayAuthMethod.NONE, controller.create(authentication, input).authMethod)
        assertEquals(GatewayAuthMethod.NONE, controller.update(authentication, route.id, input, route.version).authMethod)
    }

    @Test
    fun `interactive principals unrestricted tokens and public reads retain access`() = runTest {
        permissions = listOf(GatewayPermission(gateway.id, team.id, PermissionAction.EDIT, UUID.random()))
        val interactive = context(AuthenticatedPrincipal(Principal(id = UUID.random()), listOf(team)))
        assertTrue(evaluator.isAllowed(interactive, gateway, PermissionAction.EDIT))
        assertTrue(evaluator.isAllowed(authentication(null, team), gateway, PermissionAction.EDIT))
        assertTrue(evaluator.isAllowed(authentication(emptyList(), team), gateway.copy(public = true), PermissionAction.VIEW))
    }
}
