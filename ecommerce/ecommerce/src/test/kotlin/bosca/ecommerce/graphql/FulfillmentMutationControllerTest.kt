package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.FulfillmentCenterInput
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryInput
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.InventoryService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Fulfillment mutations: center create/edit and inventory create gate on the ecom-admin group and
 * delegate to their services; the `inventory`/`shipment` accessors return the id-scoped namespaces.
 */
@OptIn(ExperimentalUuidApi::class)
class FulfillmentMutationControllerTest {

    private val fulfillmentService = mockk<FulfillmentService>()
    private val inventoryService = mockk<InventoryService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = FulfillmentMutationController(fulfillmentService, inventoryService, groups)

    private val companyId = UUID.random()
    private val centerId = UUID.random()
    private val shippingProviderId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null // -> principalId is null
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    private fun centerInput() = FulfillmentCenterInput(
        companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = shippingProviderId,
        address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
    )

    private fun center() = FulfillmentCenter(
        id = centerId, companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = shippingProviderId,
        address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
    )

    private fun inventoryInput() = InventoryInput(
        productId = UUID.random(), fulfillmentCenterId = centerId, sku = "SKU", quantity = 5,
    )

    private fun inventory() = Inventory(
        id = UUID.random(), productId = UUID.random(), fulfillmentCenterId = centerId, sku = "SKU", quantity = 5,
    )

    @Test
    fun `addCenter gates on admin then creates via the service`() = runTest {
        coEvery { fulfillmentService.addCenter(centerInput(), null) } returns center()

        val result = controller.addCenter(auth, centerInput())

        assertEquals(centerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { fulfillmentService.addCenter(centerInput(), null) }
    }

    @Test
    fun `editCenter gates on admin then edits via the service`() = runTest {
        coEvery { fulfillmentService.editCenter(centerId, centerInput(), null) } returns center()

        val result = controller.editCenter(auth, centerId, centerInput())

        assertEquals(centerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { fulfillmentService.editCenter(centerId, centerInput(), null) }
    }

    @Test
    fun `addInventory gates on admin then creates via the service`() = runTest {
        coEvery { inventoryService.addInventory(any(), null) } returns inventory()

        val result = controller.addInventory(auth, inventoryInput())

        assertEquals(5, result.quantity)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { inventoryService.addInventory(any(), null) }
    }

    @Test
    fun `addCenter delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { fulfillmentService.addCenter(centerInput(), principalId) } returns center()

        controller.addCenter(auth, centerInput())

        coVerify(exactly = 1) { fulfillmentService.addCenter(centerInput(), principalId) }
    }

    @Test
    fun `editCenter delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { fulfillmentService.editCenter(centerId, centerInput(), principalId) } returns center()

        controller.editCenter(auth, centerId, centerInput())

        coVerify(exactly = 1) { fulfillmentService.editCenter(centerId, centerInput(), principalId) }
    }

    @Test
    fun `addInventory delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { inventoryService.addInventory(any(), principalId) } returns inventory()

        controller.addInventory(auth, inventoryInput())

        coVerify(exactly = 1) { inventoryService.addInventory(any(), principalId) }
    }

    @Test
    fun `inventory accessor returns the id-scoped mutation namespace`() {
        val id = UUID.random()
        assertEquals(id, controller.inventory(id).id)
    }

    @Test
    fun `shipment accessor returns the id-scoped mutation namespace`() {
        val id = UUID.random()
        assertEquals(id, controller.shipment(id).id)
    }
}
