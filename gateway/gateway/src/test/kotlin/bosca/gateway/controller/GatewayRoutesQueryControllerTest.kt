package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayNotFoundException
import bosca.gateway.model.GatewayRoute
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
import kotlin.test.assertNull

/**
 * Security-critical regression coverage for [GatewayRoutesQueryController].
 *
 * The first review of this code found three issues that the tests
 * here pin:
 *
 * 1. The `all` query was previously N+1 — it called `getById` for
 *    every distinct gateway. It must now route through the batched
 *    `getByIds` so adding routes doesn't blow up the round-trip
 *    count linearly.
 * 2. The `all` query must filter results through the parent Gateway's
 *    VIEW permission — routes whose parent is invisible to the
 *    caller must be excluded.
 * 3. `byGateway` must `verifyAllowed` (throwing) rather than
 *    `isAllowed` (returning empty), so callers get a clear permission
 *    error instead of an ambiguous empty list.
 */
class GatewayRoutesQueryControllerTest {

    @BeforeTest
    fun setup() {
        controller = GatewayRoutesQueryController(
            service = service,
            gatewayService = gatewayService,
            permissionEvaluator = permissionEvaluator,
        )
    }

    private val service = mockk<GatewayRouteService>()
    private val gatewayService = mockk<GatewayService>()
    private val permissionEvaluator = mockk<GatewayPermissionEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private lateinit var controller: GatewayRoutesQueryController

    private val gatewayAId = UUID.random()
    private val gatewayBId = UUID.random()
    private val gatewayA = Gateway(id = gatewayAId, name = "a", url = "http://a")
    private val gatewayB = Gateway(id = gatewayBId, name = "b", url = "http://b")

    private fun route(id: UUID = UUID.random(), gatewayId: UUID): GatewayRoute = GatewayRoute(
        id = id,
        gatewayId = gatewayId,
        pathPattern = "/p$id",
        authMethod = GatewayAuthMethod.JWT,
    )

    @Test
    fun `all batches parent lookups via getByIds`() = runTest {
        val routes = listOf(
            route(gatewayId = gatewayAId),
            route(gatewayId = gatewayAId),
            route(gatewayId = gatewayBId),
        )
        coEvery { service.listAll() } returns routes
        coEvery { gatewayService.getByIds(any()) } returns listOf(gatewayA, gatewayB)
        coEvery {
            permissionEvaluator.filterAllowed(authentication, any<List<Gateway>>(), PermissionAction.VIEW)
        } returns listOf(gatewayA, gatewayB)

        controller.all(authentication)

        // Verify the controller uses the batch loader once, not
        // `getById` per route. The deduplication of gateway IDs is
        // also exercised here — three routes across two gateways
        // must produce a single getByIds call with two distinct IDs.
        coVerify(exactly = 1) { gatewayService.getByIds(match { it.toSet() == setOf(gatewayAId, gatewayBId) }) }
        coVerify(exactly = 0) { gatewayService.getById(any()) }
    }

    @Test
    fun `all filters out routes whose parent gateway is not VIEW-allowed`() = runTest {
        val visibleRoute = route(gatewayId = gatewayAId)
        val hiddenRoute = route(gatewayId = gatewayBId)
        coEvery { service.listAll() } returns listOf(visibleRoute, hiddenRoute)
        coEvery { gatewayService.getByIds(any()) } returns listOf(gatewayA, gatewayB)
        // Only gateway A passes the VIEW filter.
        coEvery {
            permissionEvaluator.filterAllowed(authentication, any<List<Gateway>>(), PermissionAction.VIEW)
        } returns listOf(gatewayA)

        val result = controller.all(authentication)

        assertEquals(listOf(visibleRoute), result)
    }

    @Test
    fun `all returns empty when there are no routes`() = runTest {
        coEvery { service.listAll() } returns emptyList()

        val result = controller.all(authentication)

        assertEquals(emptyList(), result)
        // The empty path must NOT call the batch loader or the
        // permission evaluator — both would be wasted work.
        coVerify(exactly = 0) { gatewayService.getByIds(any()) }
        coVerify(exactly = 0) { permissionEvaluator.filterAllowed(any(), any<List<Gateway>>(), any()) }
    }

    @Test
    fun `route returns null when parent gateway is missing`() = runTest {
        val orphan = route(gatewayId = gatewayAId)
        coEvery { service.getById(orphan.id) } returns orphan
        coEvery { gatewayService.getById(gatewayAId) } returns null

        assertNull(controller.route(authentication, orphan.id))
    }

    @Test
    fun `route returns null when caller lacks VIEW on parent gateway`() = runTest {
        val r = route(gatewayId = gatewayAId)
        coEvery { service.getById(r.id) } returns r
        coEvery { gatewayService.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns false

        assertNull(controller.route(authentication, r.id))
    }

    @Test
    fun `route returns the route when VIEW is allowed`() = runTest {
        val r = route(gatewayId = gatewayAId)
        coEvery { service.getById(r.id) } returns r
        coEvery { gatewayService.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns true

        assertEquals(r, controller.route(authentication, r.id))
    }

    @Test
    fun `byGateway throws GatewayNotFound when the parent is missing`() = runTest {
        coEvery { gatewayService.getById(gatewayAId) } returns null

        assertFailsWith<GatewayNotFoundException> {
            controller.byGateway(authentication, gatewayAId)
        }
        // No data must leak before the not-found check.
        coVerify(exactly = 0) { service.listByGatewayId(any()) }
    }

    @Test
    fun `byGateway uses verifyAllowed not isAllowed so denial throws`() = runTest {
        coEvery { gatewayService.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } throws SecurityException("denied")

        // The contract: a forbidden caller must get a permission error,
        // not silently see an empty list (which would be ambiguous —
        // empty could mean "no routes" or "you can't see").
        assertFailsWith<SecurityException> {
            controller.byGateway(authentication, gatewayAId)
        }
        coVerify(exactly = 0) { service.listByGatewayId(any()) }
    }

    @Test
    fun `byGateway returns routes when VIEW is allowed`() = runTest {
        coEvery { gatewayService.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } just Runs
        val routes = listOf(route(gatewayId = gatewayAId), route(gatewayId = gatewayAId))
        coEvery { service.listByGatewayId(gatewayAId) } returns routes

        val result = controller.byGateway(authentication, gatewayAId)

        assertEquals(routes, result)
    }
}
