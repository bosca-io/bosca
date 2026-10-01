package bosca.ecommerce.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.StandardProductConfiguration
import bosca.ecommerce.service.InventoryService
import bosca.ecommerce.service.ProductService
import bosca.graphql.Batch
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Product field wiring: scalar fields read the source; `company`/`manufacturer` delegate to the
 * service's cached batch loaders; `metadata` resolves through its service; `inventory` is admin-gated.
 */
@OptIn(ExperimentalUuidApi::class)
class ProductControllerTest {

    private val productService = mockk<ProductService>()
    private val metadataService = mockk<MetadataService>()
    private val inventoryService = mockk<InventoryService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ProductController(
        productService, metadataService, inventoryService, groups,
    )

    private val companyId = UUID.random()
    private val manufacturerId = UUID.random()
    private val metadataId = UUID.random()
    private val product = Product(
        id = UUID.random(), companyId = companyId, manufacturerId = manufacturerId,
        manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 3, type = ProductType.PHYSICAL,
        weight = 2.5, width = 1.0, height = 2.0, length = 3.0,
    )

    @Test
    fun `scalar fields resolve from the source product`() {
        assertEquals(product.id, controller.id(product))
        assertEquals("SKU1", controller.manufacturerSku(product))
        assertEquals(ProductType.PHYSICAL, controller.type(product))
        assertEquals(StandardProductConfiguration, controller.configuration(product))
        assertEquals(2.5, controller.weight(product))
        assertEquals(1.0, controller.width(product))
        assertEquals(2.0, controller.height(product))
        assertEquals(3.0, controller.length(product))
        assertEquals(3, controller.metadataVersion(product))
        assertEquals(product.created, controller.created(product))
        assertEquals(product.modified, controller.modified(product))
    }

    @Test
    fun `company delegates to the service's cached batch loader`() = runTest {
        val batch = Batch<UUID, Company>(listOf(product.id))
        coEvery { productService.addCompaniesToBatch(batch) } returns Unit
        controller.company(batch)
        coVerify(exactly = 1) { productService.addCompaniesToBatch(batch) }
    }

    @Test
    fun `manufacturer delegates to the service's cached batch loader`() = runTest {
        val batch = Batch<UUID, Manufacturer>(listOf(product.id))
        coEvery { productService.addManufacturersToBatch(batch) } returns Unit
        controller.manufacturer(batch)
        coVerify(exactly = 1) { productService.addManufacturersToBatch(batch) }
    }

    @Test
    fun `metadata resolves at the pinned version`() = runTest {
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId, 3) } returns metadata
        assertEquals(metadata, controller.metadata(product))
    }

    @Test
    fun `metadata errors when missing`() = runTest {
        coEvery { metadataService.getById(metadataId, 3) } returns null
        assertFailsWith<IllegalStateException> { controller.metadata(product) }
    }

    @Test
    fun `inventory gates on admin then resolves through the service`() = runTest {
        val rows = listOf(
            Inventory(id = UUID.random(), productId = product.id, fulfillmentCenterId = UUID.random(), sku = "SKU1"),
        )
        coEvery { inventoryService.getByProduct(product.id) } returns rows

        assertEquals(rows, controller.inventory(auth, product))
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
    }
}
