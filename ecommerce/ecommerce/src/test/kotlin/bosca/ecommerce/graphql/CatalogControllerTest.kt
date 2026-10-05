package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.graphql.Batch
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Catalog field wiring: scalar fields read the source; `company` delegates to the service's cached
 * batch loader; `products` forwards its paging/filter arguments to the catalog-product service.
 */
@OptIn(ExperimentalUuidApi::class)
class CatalogControllerTest {

    private val catalogService = mockk<CatalogService>()
    private val catalogProductService = mockk<CatalogProductService>()
    private val controller = CatalogController(catalogService, catalogProductService)

    private val companyId = UUID.random()
    private val catalog = Catalog(id = UUID.random(), companyId = companyId, key = "default", name = "Default")

    @Test
    fun `scalar fields resolve from the source catalog`() {
        assertEquals(catalog.id, controller.id(catalog))
        assertEquals("default", controller.key(catalog))
        assertEquals("Default", controller.name(catalog))
        assertEquals(catalog.created, controller.created(catalog))
        assertEquals(catalog.modified, controller.modified(catalog))
    }

    @Test
    fun `company delegates to the service's cached batch loader`() = runTest {
        val batch = Batch<UUID, Company>(listOf(catalog.id))
        coEvery { catalogService.addCompaniesToBatch(batch) } returns Unit
        controller.company(batch)
        coVerify(exactly = 1) { catalogService.addCompaniesToBatch(batch) }
    }

    @Test
    fun `products forwards filter and paging args to the service`() = runTest {
        val entries = listOf(
            CatalogProduct(
                id = UUID.random(), catalogId = catalog.id, productId = UUID.random(),
                type = ProductType.PHYSICAL, price = Money.of("9.99"),
            ),
        )
        coEvery {
            catalogProductService.getByCatalog(catalog.id, ProductType.PHYSICAL, true, 5, 20)
        } returns entries

        val result = controller.products(catalog, ProductType.PHYSICAL, activeOnly = true, offset = 5, limit = 20)

        assertEquals(entries, result)
    }
}
