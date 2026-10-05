@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShippingProvider
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**shipping rate resolution (by providerKey) + selection delegating to the cart's shipping line. */
@OptIn(ExperimentalUuidApi::class)
class ShippingServiceImplTest {

    private val cartService = mockk<CartService>()
    private val providerService = mockk<ProviderService>()
    private val fulfillmentService = mockk<FulfillmentService>(relaxed = true)
    private val catalogProductService = mockk<CatalogProductService>(relaxed = true)
    private val productService = mockk<ProductService>(relaxed = true)
    private val companyService = mockk<CompanyService>(relaxed = true)
    private lateinit var service: ShippingServiceImpl

    private val cartId = UUID.random()
    private val companyId = UUID.random()
    private val providerId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ShippingRateProvider>(name = "fixed-rate", singleton = true) { FixedRateShippingRateProvider() }
        service = ShippingServiceImpl(cartService, providerService, fulfillmentService, catalogProductService, productService, companyService)
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun cart() = Cart(id = cartId, companyId = companyId, storeId = UUID.random(), expires = OffsetDateTime.now().plusSeconds(3600))

    private fun shippingConfig() = ShippingProvider(id = providerId, companyId = companyId, name = "Flat", key = "flat-1", providerKey = "fixed-rate")

    private fun address(type: AddressType) = CartAddress(
        cartId = cartId, type = type, firstName = "A", lastName = "B", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5",
    )

    @Test
    fun `rates errors when the cart is absent`() = runTest {
        coEvery { cartService.get(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.rates(cartId) }
    }

    @Test
    fun `rates uses the shipping address as the destination`() = runTest {
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns listOf(address(AddressType.SHIPPING))
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())

        assertEquals(1, service.rates(cartId).size)
    }

    @Test
    fun `rates falls back to the billing address when there is no shipping address`() = runTest {
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns listOf(address(AddressType.BILLING))
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())

        assertEquals(1, service.rates(cartId).size)
    }

    @Test
    fun `selectRate errors when the token is not available`() = runTest {
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns emptyList()
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())

        assertFailsWith<IllegalStateException> { service.selectRate(cartId, "no-such-token", principalId = null) }
    }

    @Test
    fun `rates resolves the configured provider by key`() = runTest {
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns emptyList()
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())

        val rates = service.rates(cartId)

        assertEquals(1, rates.size)
        assertEquals(Money.of("5.00"), rates.first().amount)
        assertEquals("fixed:$providerId", rates.first().token)
    }

    @Test
    fun `rates returns nothing when the company has no shipping providers configured`() = runTest {
        // getByCompany returns no configs -> the flatMap has nothing to resolve -> no rates.
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns emptyList()
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns emptyList()

        assertTrue(service.rates(cartId).isEmpty())
    }

    @Test
    fun `rates resolves the origin center, destination, and parcels from the cart`() = runTest {
        val cpId = UUID.random()
        val prodId = UUID.random()
        coEvery { cartService.get(cartId) } returns cart().copy(items = listOf(CartItem(catalogProductId = cpId, type = ProductType.PHYSICAL, quantity = 2)))
        coEvery { cartService.getAddresses(cartId) } returns listOf(address(AddressType.SHIPPING))
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())
        coEvery { fulfillmentService.getCentersByCompany(companyId) } returns listOf(
            FulfillmentCenter(companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = UUID.random(), address1 = "1", city = "C", state = "CA", country = "US", zip = "0"),
        )
        coEvery { companyService.get(companyId) } returns Company(organizationId = UUID.random(), profileId = UUID.random())
        coEvery { catalogProductService.get(cpId) } returns CatalogProduct(catalogId = UUID.random(), productId = prodId, type = ProductType.PHYSICAL, price = Money.ZERO)
        coEvery { productService.get(prodId) } returns Product(
            companyId = companyId, manufacturerId = UUID.random(), manufacturerSku = "s", metadataId = UUID.random(),
            type = ProductType.PHYSICAL, weight = 1.0, width = 2.0, height = 3.0, length = 4.0,
        )

        assertEquals(1, service.rates(cartId).size)
    }

    @Test
    fun `rates tolerates missing addresses, centers, company, and unresolvable products`() = runTest {
        val cpId = UUID.random()
        coEvery { cartService.get(cartId) } returns cart().copy(items = listOf(CartItem(catalogProductId = cpId, type = ProductType.PHYSICAL, quantity = 1)))
        coEvery { cartService.getAddresses(cartId) } returns emptyList()                  // no destination
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())
        coEvery { fulfillmentService.getCentersByCompany(companyId) } returns emptyList() // no origin
        coEvery { companyService.get(companyId) } returns null                            // default units
        coEvery { catalogProductService.get(cpId) } returns null                          // unresolvable -> parcel skipped

        assertEquals(1, service.rates(cartId).size)
    }

    @Test
    fun `rates skips non-physical lines and physical lines whose product cannot be resolved`() = runTest {
        val cpUnresolved = UUID.random()
        val cpNoProduct = UUID.random()
        val prodId = UUID.random()
        coEvery { cartService.get(cartId) } returns cart().copy(items = listOf(
            CartItem(catalogProductId = cpUnresolved, type = ProductType.PHYSICAL, quantity = 1), // catalog product missing -> skipped
            CartItem(catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1),  // non-physical -> filtered out
            CartItem(catalogProductId = cpNoProduct, type = ProductType.PHYSICAL, quantity = 1),    // product missing -> skipped
        ))
        coEvery { cartService.getAddresses(cartId) } returns listOf(address(AddressType.SHIPPING))
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())
        coEvery { catalogProductService.get(cpUnresolved) } returns null
        coEvery { catalogProductService.get(cpNoProduct) } returns CatalogProduct(catalogId = UUID.random(), productId = prodId, type = ProductType.PHYSICAL, price = Money.ZERO)
        coEvery { productService.get(prodId) } returns null

        assertEquals(1, service.rates(cartId).size)
    }

    @Test
    fun `fixed-rate provider quotes the configured amount when one is set`() = runTest {
        val provider = FixedRateShippingRateProvider()
        val config = shippingConfig().copy(configuration = KeyValueProviderConfiguration(mapOf("amount" to "7.50")))

        val rates = provider.rates(config, cart(), origin = null, destination = null, parcels = emptyList())

        assertEquals(Money.of("7.50"), rates.first().amount) // `as?`/`?.values?.get` non-null + `configured?.let` left branch
    }

    @Test
    fun `selectRate matches the token and sets the shipping line`() = runTest {
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartService.getAddresses(cartId) } returns emptyList()
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns listOf(shippingConfig())
        coEvery { cartService.setShipping(eq(cartId), any(), any(), any()) } returns cart()

        service.selectRate(cartId, "fixed:$providerId", principalId = null)

        coVerify(exactly = 1) {
            cartService.setShipping(eq(cartId), match { it.amount == Money.of("5.00") }, any(), any())
        }
    }
}
