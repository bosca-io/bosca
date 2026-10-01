package bosca.ecommerce.graphql

import bosca.ecommerce.model.Inventory
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

/** Inventory mutations: each gates on the ecom-admin group, then delegates to the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class InventoryMutationControllerTest {

    private val inventoryService = mockk<InventoryService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = InventoryMutationController(inventoryService, groups)

    private val inventoryId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null // -> principalId is null
    }

    private fun inventory(quantity: Int) = Inventory(
        id = inventoryId, productId = UUID.random(), fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = quantity,
    )

    @Test
    fun `adjust gates on admin then adjusts via the service`() = runTest {
        coEvery { inventoryService.adjust(inventoryId, 10, null) } returns inventory(10)

        val result = controller.adjust(auth, InventoryMutation(inventoryId), 10)

        assertEquals(10, result.quantity)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { inventoryService.adjust(inventoryId, 10, null) }
    }

    @Test
    fun `ship gates on admin then ships via the service`() = runTest {
        coEvery { inventoryService.ship(inventoryId, 3, null) } returns inventory(7)

        val result = controller.ship(auth, InventoryMutation(inventoryId), 3)

        assertEquals(7, result.quantity)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { inventoryService.ship(inventoryId, 3, null) }
    }

    @Test
    fun `adjust forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { inventoryService.adjust(inventoryId, 10, principalId) } returns inventory(10)

        controller.adjust(auth, InventoryMutation(inventoryId), 10)

        coVerify(exactly = 1) { inventoryService.adjust(inventoryId, 10, principalId) }
    }

    @Test
    fun `ship forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { inventoryService.ship(inventoryId, 3, principalId) } returns inventory(7)

        controller.ship(auth, InventoryMutation(inventoryId), 3)

        coVerify(exactly = 1) { inventoryService.ship(inventoryId, 3, principalId) }
    }
}
