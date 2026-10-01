package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.ProviderService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Store field wiring: scalars passthrough; relations resolve via their services. */
@OptIn(ExperimentalUuidApi::class)
class StoreControllerTest {

    private val companyService = mockk<CompanyService>()
    private val catalogService = mockk<CatalogService>()
    private val catalogProductService = mockk<CatalogProductService>()
    private val providerService = mockk<ProviderService>()
    private val controller = StoreController(companyService, catalogService, catalogProductService, providerService)

    private val companyId = UUID.random()
    private val catalogId = UUID.random()
    private val paymentProviderId = UUID.random()
    private val shippingCatalogProductId = UUID.random()
    private val store = Store(
        id = UUID.random(), identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId,
        type = StoreType.PHYSICAL, paymentProviderId = paymentProviderId,
        shippingCatalogProductId = shippingCatalogProductId, cartExpirationSeconds = 7200,
    )

    @Test
    fun `scalar fields resolve from the source store`() {
        assertEquals(store.id, controller.id(store))
        assertEquals("shop", controller.identifier(store))
        assertEquals("Shop", controller.name(store))
        assertEquals(StoreType.PHYSICAL, controller.type(store))
        assertEquals(7200, controller.cartExpirationSeconds(store))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        coEvery { companyService.get(companyId) } returns
            Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        assertEquals(companyId, controller.company(store).id)
    }

    @Test
    fun `company errors when missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(store) }
    }

    @Test
    fun `catalog resolves via the catalog service`() = runTest {
        coEvery { catalogService.get(catalogId) } returns
            Catalog(id = catalogId, companyId = companyId, key = "k", name = "Cat")
        assertEquals(catalogId, controller.catalog(store).id)
    }

    @Test
    fun `catalog errors when missing`() = runTest {
        coEvery { catalogService.get(catalogId) } returns null
        assertFailsWith<IllegalStateException> { controller.catalog(store) }
    }

    @Test
    fun `paymentProvider resolves via the provider service and may be null`() = runTest {
        coEvery { providerService.getPaymentProvider(paymentProviderId) } returns
            PaymentProvider(id = paymentProviderId, companyId = companyId, name = "Pay", providerKey = "test")
        assertEquals(paymentProviderId, controller.paymentProvider(store)?.id)

        coEvery { providerService.getPaymentProvider(paymentProviderId) } returns null
        assertNull(controller.paymentProvider(store))
    }

    @Test
    fun `shippingCatalogProduct resolves via the catalog product service`() = runTest {
        coEvery { catalogProductService.get(shippingCatalogProductId) } returns
            CatalogProduct(
                id = shippingCatalogProductId, catalogId = catalogId, productId = UUID.random(),
                type = ProductType.SHIPPING, price = Money.of("0.00"),
            )
        assertEquals(shippingCatalogProductId, controller.shippingCatalogProduct(store).id)
    }

    @Test
    fun `shippingCatalogProduct errors when missing`() = runTest {
        coEvery { catalogProductService.get(shippingCatalogProductId) } returns null
        assertFailsWith<IllegalStateException> { controller.shippingCatalogProduct(store) }
    }
}
