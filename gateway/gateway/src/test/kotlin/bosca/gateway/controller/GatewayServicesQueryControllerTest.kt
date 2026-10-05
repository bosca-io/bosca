package bosca.gateway.controller

import bosca.gateway.model.Gateway
import bosca.gateway.service.GatewayPermissionEvaluator
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
import kotlin.test.assertNull

/**
 * Query-controller coverage for [Gateway] reads.
 *
 * Three patterns are exercised:
 *
 * 1. `all` uses `filterAllowed(VIEW)` so the response excludes
 *    gateways the caller cannot see. The list query NEVER throws on
 *    a partial-access situation — it returns the subset.
 * 2. Single-id and by-name lookups use `isAllowed(VIEW)` and return
 *    `null` on denial, masking existence rather than throwing.
 *    Returning `Forbidden` here would leak which IDs/names exist.
 * 3. A row that doesn't exist also returns `null` — same surface as
 *    a row the caller can't see. This is intentional.
 */
class GatewayServicesQueryControllerTest {

    @BeforeTest
    fun setup() {
        controller = GatewayServicesQueryController(
            service = service,
            permissionEvaluator = permissionEvaluator,
        )
    }

    private val service = mockk<GatewayService>()
    private val permissionEvaluator = mockk<GatewayPermissionEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private lateinit var controller: GatewayServicesQueryController

    private val gatewayAId = UUID.random()
    private val gatewayBId = UUID.random()
    private val gatewayA = Gateway(id = gatewayAId, name = "a", url = "http://a")
    private val gatewayB = Gateway(id = gatewayBId, name = "b", url = "http://b")

    @Test
    fun `all returns the VIEW-filtered subset`() = runTest {
        coEvery { service.listAll() } returns listOf(gatewayA, gatewayB)
        coEvery {
            permissionEvaluator.filterAllowed(authentication, listOf(gatewayA, gatewayB), PermissionAction.VIEW)
        } returns listOf(gatewayA)

        val result = controller.all(authentication)

        assertEquals(listOf(gatewayA), result)
    }

    @Test
    fun `all returns empty when nothing is allowed`() = runTest {
        coEvery { service.listAll() } returns listOf(gatewayA, gatewayB)
        coEvery {
            permissionEvaluator.filterAllowed(authentication, any<List<Gateway>>(), PermissionAction.VIEW)
        } returns emptyList()

        assertEquals(emptyList(), controller.all(authentication))
    }

    @Test
    fun `gateway by id returns null when the row doesn't exist`() = runTest {
        coEvery { service.getById(gatewayAId) } returns null

        assertNull(controller.gateway(authentication, gatewayAId))
        // Existence check must come BEFORE permission probe — otherwise
        // we waste a permission lookup on a no-op.
        coVerify(exactly = 0) { permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Gateway>(), any<PermissionAction>()) }
    }

    @Test
    fun `gateway by id returns null when VIEW is denied (existence is masked)`() = runTest {
        coEvery { service.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns false

        // Returning null (vs throwing Forbidden) is intentional: the
        // two failure modes — "no such gateway" and "you can't see
        // it" — look identical to the caller, so an attacker probing
        // UUIDs can't enumerate which IDs exist.
        assertNull(controller.gateway(authentication, gatewayAId))
    }

    @Test
    fun `gateway by id returns the row when VIEW is allowed`() = runTest {
        coEvery { service.getById(gatewayAId) } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns true

        assertEquals(gatewayA, controller.gateway(authentication, gatewayAId))
    }

    @Test
    fun `gatewayByName masks existence on permission denial`() = runTest {
        coEvery { service.getByName("a") } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns false

        assertNull(controller.gatewayByName(authentication, "a"))
    }

    @Test
    fun `gatewayByName returns null when the row doesn't exist`() = runTest {
        coEvery { service.getByName("missing") } returns null

        assertNull(controller.gatewayByName(authentication, "missing"))
        coVerify(exactly = 0) { permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Gateway>(), any<PermissionAction>()) }
    }

    @Test
    fun `gatewayByName returns the row when VIEW is allowed`() = runTest {
        coEvery { service.getByName("a") } returns gatewayA
        coEvery {
            permissionEvaluator.isAllowed(authentication, gatewayA, PermissionAction.VIEW)
        } returns true

        assertEquals(gatewayA, controller.gatewayByName(authentication, "a"))
    }
}
