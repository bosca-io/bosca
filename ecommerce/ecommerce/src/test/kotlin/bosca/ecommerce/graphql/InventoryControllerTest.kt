package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.ProductService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Inventory field wiring: scalar fields read the source row (including derived `available`); `product`
 * and `fulfillmentCenter` resolve through their services and error when missing.
 */
@OptIn(ExperimentalUuidApi::class)
class InventoryControllerTest {

    private val productService = mockk<ProductService>()
    private val fulfillmentService = mockk<FulfillmentService>()
    private val controller = InventoryController(productService, fulfillmentService)

    private val productId = UUID.random()
    private val centerId = UUID.random()
    private val inventory = Inventory(
        id = UUID.random(), productId = productId, fulfillmentCenterId = centerId,
        sku = "SKU", quantity = 10, pending = 2, inCart = 3,
    )

    @Test
    fun `scalar fields resolve from the source inventory`() {
        assertEquals(inventory.id, controller.id(inventory))
        assertEquals("SKU", controller.sku(inventory))
        assertEquals(10, controller.quantity(inventory))
        assertEquals(2, controller.pending(inventory))
        assertEquals(3, controller.inCart(inventory))
        assertEquals(5, controller.available(inventory)) // 10 - 2 - 3
    }

    @Test
    fun `product resolves through the service`() = runTest {
        val product = Product(
            id = productId, companyId = UUID.random(), manufacturerId = UUID.random(), manufacturerSku = "s",
            metadataId = UUID.random(), type = ProductType.PHYSICAL,
        )
        coEvery { productService.get(productId) } returns product
        assertEquals(product, controller.product(inventory))
    }

    @Test
    fun `product errors when missing`() = runTest {
        coEvery { productService.get(productId) } returns null
        assertFailsWith<IllegalStateException> { controller.product(inventory) }
    }

    @Test
    fun `fulfillmentCenter resolves through the service`() = runTest {
        val center = FulfillmentCenter(
            id = centerId, companyId = UUID.random(), name = "DC", connectorKey = "manual",
            shippingProviderId = UUID.random(), address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
        )
        coEvery { fulfillmentService.getCenter(centerId) } returns center
        assertEquals(center, controller.fulfillmentCenter(inventory))
    }

    @Test
    fun `fulfillmentCenter errors when missing`() = runTest {
        coEvery { fulfillmentService.getCenter(centerId) } returns null
        assertFailsWith<IllegalStateException> { controller.fulfillmentCenter(inventory) }
    }
}
