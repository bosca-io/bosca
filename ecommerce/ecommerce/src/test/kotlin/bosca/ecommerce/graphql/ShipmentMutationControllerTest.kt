@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.graphql

import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.ShipmentService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Shipment mutations: each gates on the ecom-admin group, then delegates to the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class ShipmentMutationControllerTest {

    private val shipmentService = mockk<ShipmentService>()
    private val cartService = mockk<CartService>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ShipmentMutationController(shipmentService, cartService, groups)

    private val shipmentId = UUID.random()
    private val cartId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null // -> principalId is null
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    private fun shipment(status: ShipmentStatus = ShipmentStatus.SHIPPED) = Shipment(
        id = shipmentId, cartId = cartId, storeId = UUID.random(), companyId = UUID.random(),
        fulfillmentCenterId = UUID.random(), status = status,
    )

    @Test
    fun `ship gates on admin, ships, then advances fulfillment in one transaction`() = runTest {
        val shipped = shipment()
        coEvery { shipmentService.ship(shipmentId, "UPS", "1Z999", null) } returns shipped

        val result = controller.ship(auth, ShipmentMutation(shipmentId), "UPS", "1Z999")

        assertEquals(shipmentId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { shipmentService.ship(shipmentId, "UPS", "1Z999", null) }
        coVerify(exactly = 1) { cartService.advanceFulfillment(cartId, null) }
    }

    @Test
    fun `refreshTracking gates on admin then refreshes via the service`() = runTest {
        coEvery { shipmentService.refreshTracking(shipmentId, null) } returns shipment(ShipmentStatus.IN_TRANSIT)

        val result = controller.refreshTracking(auth, ShipmentMutation(shipmentId))

        assertEquals(ShipmentStatus.IN_TRANSIT, result.status)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { shipmentService.refreshTracking(shipmentId, null) }
    }

    @Test
    fun `repack gates on admin then repacks via the service`() = runTest {
        coEvery { shipmentService.repack(shipmentId, null) } returns shipment(ShipmentStatus.AWAITING)

        val result = controller.repack(auth, ShipmentMutation(shipmentId))

        assertEquals(ShipmentStatus.AWAITING, result.status)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { shipmentService.repack(shipmentId, null) }
    }

    @Test
    fun `ship with the authenticated principal id and provider-assigned carrier advances fulfillment`() = runTest {
        authenticated()
        // carrier/tracking omitted -> provider-assigned (default null args), principal non-null.
        coEvery { shipmentService.ship(shipmentId, null, null, principalId) } returns shipment()

        val result = controller.ship(auth, ShipmentMutation(shipmentId))

        assertEquals(shipmentId, result.id)
        coVerify(exactly = 1) { shipmentService.ship(shipmentId, null, null, principalId) }
        coVerify(exactly = 1) { cartService.advanceFulfillment(cartId, principalId) }
    }

    @Test
    fun `refreshTracking delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { shipmentService.refreshTracking(shipmentId, principalId) } returns shipment(ShipmentStatus.IN_TRANSIT)

        controller.refreshTracking(auth, ShipmentMutation(shipmentId))

        coVerify(exactly = 1) { shipmentService.refreshTracking(shipmentId, principalId) }
    }

    @Test
    fun `repack delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { shipmentService.repack(shipmentId, principalId) } returns shipment(ShipmentStatus.AWAITING)

        controller.repack(auth, ShipmentMutation(shipmentId))

        coVerify(exactly = 1) { shipmentService.repack(shipmentId, principalId) }
    }
}
