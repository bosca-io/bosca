package bosca.ecommerce.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.EmptyCatalogProductExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.ProductService
import bosca.graphql.Batch
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * CatalogProduct field wiring: scalar fields read the source; `catalog`/`product` resolve through
 * their services; `metadata` resolves the backing product's content document at the pinned version.
 */
@OptIn(ExperimentalUuidApi::class)
class CatalogProductControllerTest {

    private val catalogProductService = mockk<CatalogProductService>()
    private val productService = mockk<ProductService>()
    private val metadataService = mockk<MetadataService>()
    private val controller = CatalogProductController(catalogProductService, productService, metadataService)

    private val catalogId = UUID.random()
    private val productId = UUID.random()
    private val metadataId = UUID.random()
    private val entry = CatalogProduct(
        id = UUID.random(), catalogId = catalogId, productId = productId,
        type = ProductType.PHYSICAL, price = Money.of("9.99"), taxable = true,
        promotions = listOf("PROMO1"),
    )

    private fun product() = Product(
        id = productId, companyId = UUID.random(), manufacturerId = UUID.random(), manufacturerSku = "SKU1",
        metadataId = metadataId, metadataVersion = 4, type = ProductType.PHYSICAL,
    )

    @Test
    fun `scalar fields resolve from the source entry`() {
        assertEquals(entry.id, controller.id(entry))
        assertEquals(ProductType.PHYSICAL, controller.type(entry))
        assertEquals(Money.of("9.99"), controller.price(entry))
        assertEquals(true, controller.taxable(entry))
        assertEquals(entry.starts, controller.starts(entry))
        assertEquals(entry.ends, controller.ends(entry))
        assertEquals(listOf("PROMO1"), controller.promotions(entry))
        assertEquals(EmptyCatalogProductExtras, controller.extras(entry))
    }

    @Test
    fun `catalog delegates to the service's cached batch loader`() = runTest {
        val batch = Batch<UUID, Catalog>(listOf(entry.id))
        coEvery { catalogProductService.addCatalogsToBatch(batch) } returns Unit
        controller.catalog(batch)
        coVerify(exactly = 1) { catalogProductService.addCatalogsToBatch(batch) }
    }

    @Test
    fun `product delegates to the service's cached batch loader`() = runTest {
        val batch = Batch<UUID, Product>(listOf(entry.id))
        coEvery { catalogProductService.addProductsToBatch(batch) } returns Unit
        controller.product(batch)
        coVerify(exactly = 1) { catalogProductService.addProductsToBatch(batch) }
    }

    @Test
    fun `metadata resolves the backing product document at its pinned version`() = runTest {
        val metadata = mockk<Metadata>()
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 4) } returns metadata
        assertEquals(metadata, controller.metadata(entry))
    }

    @Test
    fun `metadata errors when the product is missing`() = runTest {
        coEvery { productService.get(productId) } returns null
        assertFailsWith<IllegalStateException> { controller.metadata(entry) }
    }

    @Test
    fun `metadata errors when the backing document is missing`() = runTest {
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 4) } returns null
        assertFailsWith<IllegalStateException> { controller.metadata(entry) }
    }
}
