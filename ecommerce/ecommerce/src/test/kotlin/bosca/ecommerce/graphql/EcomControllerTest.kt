package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Audit
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Container
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.ContainerService
import bosca.ecommerce.service.CustomerService
import bosca.ecommerce.service.EcomAuditService
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.InventoryService
import bosca.ecommerce.service.ManufacturerService
import bosca.ecommerce.service.PaymentService
import bosca.ecommerce.service.ProductService
import bosca.ecommerce.service.PromotionService
import bosca.ecommerce.service.ProviderService
import bosca.ecommerce.service.ReturnService
import bosca.ecommerce.service.ShipmentService
import bosca.ecommerce.service.ShippingService
import bosca.ecommerce.service.StoreService
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.ecommerce.service.SubscriptionService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * `Query.ecom` resolver wiring: open merchandising reads delegate straight to the service; admin/PII
 * reads gate on the ecom administrators group then delegate; cart-scoped reads run through the
 * [CartAccessEvaluator]. Each test asserts the service delegation (and, where applicable, the gate).
 */
@OptIn(ExperimentalUuidApi::class)
class EcomControllerTest {

    private val companyService = mockk<CompanyService>()
    private val customerService = mockk<CustomerService>()
    private val accountService = mockk<AccountService>()
    private val catalogService = mockk<CatalogService>()
    private val catalogProductService = mockk<CatalogProductService>()
    private val manufacturerService = mockk<ManufacturerService>()
    private val productService = mockk<ProductService>()
    private val storeService = mockk<StoreService>()
    private val providerService = mockk<ProviderService>()
    private val inventoryService = mockk<InventoryService>()
    private val fulfillmentService = mockk<FulfillmentService>()
    private val cartService = mockk<CartService>()
    private val shipmentService = mockk<ShipmentService>()
    private val returnService = mockk<ReturnService>()
    private val containerService = mockk<ContainerService>()
    private val cartAccess = mockk<CartAccessEvaluator>(relaxed = true)
    private val promotionService = mockk<PromotionService>()
    private val shippingService = mockk<ShippingService>()
    private val paymentService = mockk<PaymentService>()
    private val auditService = mockk<EcomAuditService>()
    private val subscriptionPlanService = mockk<SubscriptionPlanService>()
    private val subscriptionService = mockk<SubscriptionService>()
    private val profileService = mockk<ProfileService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = EcomController(
        companyService = companyService,
        customerService = customerService,
        accountService = accountService,
        catalogService = catalogService,
        catalogProductService = catalogProductService,
        manufacturerService = manufacturerService,
        productService = productService,
        storeService = storeService,
        providerService = providerService,
        inventoryService = inventoryService,
        fulfillmentService = fulfillmentService,
        cartService = cartService,
        shipmentService = shipmentService,
        returnService = returnService,
        containerService = containerService,
        cartAccess = cartAccess,
        promotionService = promotionService,
        shippingService = shippingService,
        paymentService = paymentService,
        auditService = auditService,
        subscriptionPlanService = subscriptionPlanService,
        subscriptionService = subscriptionService,
        profileService = profileService,
        groups = groups,
    )

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun verifyGate() {
        verify { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) }
    }

    // ---- companies / customers / accounts (admin + open) ----

    @Test
    fun `company delegates to companyService get`() = runTest {
        val id = UUID.random()
        val company = mockk<Company>()
        coEvery { companyService.get(id) } returns company
        assertSame(company, controller.company(id))
    }

    @Test
    fun `companies gates and pages`() = runTest {
        val expected = listOf(mockk<Company>())
        coEvery { companyService.getAll(5, 10) } returns expected
        assertEquals(expected, controller.companies(auth, 5, 10))
        verifyGate()
    }

    @Test
    fun `customer gates and reads by id`() = runTest {
        val id = UUID.random()
        val customer = mockk<Customer>()
        coEvery { customerService.get(id) } returns customer
        assertSame(customer, controller.customer(auth, id))
        verifyGate()
    }

    @Test
    fun `customers gates and pages by company`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Customer>())
        coEvery { customerService.getByCompany(companyId, 0, 25) } returns expected
        assertEquals(expected, controller.customers(auth, companyId, 0, 25))
        verifyGate()
    }

    @Test
    fun `myCustomer returns null when unauthenticated`() = runTest {
        assertNull(controller.myCustomer(auth, UUID.random()))
    }

    @Test
    fun `myCustomer returns null when principal has no profile`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { profileService.getPrimaryProfile(any()) } returns null
        assertNull(controller.myCustomer(auth, UUID.random()))
    }

    @Test
    fun `myCustomer resolves principal to profile to customer`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val companyId = UUID.random()
        val customer = mockk<Customer>()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { customerService.getByProfile(companyId, profileId) } returns customer
        assertSame(customer, controller.myCustomer(auth, companyId))
    }

    @Test
    fun `myCustomer returns null when the resolved profile has no customer`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val companyId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { customerService.getByProfile(companyId, profileId) } returns null
        assertNull(controller.myCustomer(auth, companyId))
    }

    @Test
    fun `account gates and reads by id`() = runTest {
        val id = UUID.random()
        val account = mockk<Account>()
        coEvery { accountService.get(id) } returns account
        assertSame(account, controller.account(auth, id))
        verifyGate()
    }

    @Test
    fun `accounts gates and pages by company`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Account>())
        coEvery { accountService.getByCompany(companyId, 1, 2) } returns expected
        assertEquals(expected, controller.accounts(auth, companyId, 1, 2))
        verifyGate()
    }

    // ---- catalogs (open) ----

    @Test
    fun `catalog delegates to catalogService get`() = runTest {
        val id = UUID.random()
        val catalog = mockk<Catalog>()
        coEvery { catalogService.get(id) } returns catalog
        assertSame(catalog, controller.catalog(id))
    }

    @Test
    fun `catalogByKey delegates to catalogService getByKey`() = runTest {
        val companyId = UUID.random()
        val catalog = mockk<Catalog>()
        coEvery { catalogService.getByKey(companyId, "default") } returns catalog
        assertSame(catalog, controller.catalogByKey(companyId, "default"))
    }

    @Test
    fun `catalogs delegates to catalogService getByCompany`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Catalog>())
        coEvery { catalogService.getByCompany(companyId) } returns expected
        assertEquals(expected, controller.catalogs(companyId))
    }

    // ---- manufacturers / products / catalog products (open) ----

    @Test
    fun `manufacturer delegates to manufacturerService get`() = runTest {
        val id = UUID.random()
        val manufacturer = mockk<Manufacturer>()
        coEvery { manufacturerService.get(id) } returns manufacturer
        assertSame(manufacturer, controller.manufacturer(id))
    }

    @Test
    fun `manufacturers delegates to manufacturerService getByCompany`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Manufacturer>())
        coEvery { manufacturerService.getByCompany(companyId, 0, 50) } returns expected
        assertEquals(expected, controller.manufacturers(companyId, 0, 50))
    }

    @Test
    fun `product delegates to productService get`() = runTest {
        val id = UUID.random()
        val product = mockk<Product>()
        coEvery { productService.get(id) } returns product
        assertSame(product, controller.product(id))
    }

    @Test
    fun `products delegates to productService getByCompany`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Product>())
        coEvery { productService.getByCompany(companyId, 0, 20) } returns expected
        assertEquals(expected, controller.products(companyId, 0, 20))
    }

    @Test
    fun `catalogProduct delegates to catalogProductService get`() = runTest {
        val id = UUID.random()
        val catalogProduct = mockk<CatalogProduct>()
        coEvery { catalogProductService.get(id) } returns catalogProduct
        assertSame(catalogProduct, controller.catalogProduct(id))
    }

    @Test
    fun `catalogProducts delegates to catalogProductService getByCatalog`() = runTest {
        val catalogId = UUID.random()
        val expected = listOf(mockk<CatalogProduct>())
        coEvery {
            catalogProductService.getByCatalog(catalogId, ProductType.PHYSICAL, true, 0, 20)
        } returns expected
        assertEquals(expected, controller.catalogProducts(catalogId, ProductType.PHYSICAL, true, 0, 20))
    }

    // ---- stores (open) ----

    @Test
    fun `store delegates to storeService get`() = runTest {
        val id = UUID.random()
        val store = mockk<Store>()
        coEvery { storeService.get(id) } returns store
        assertSame(store, controller.store(id))
    }

    @Test
    fun `storeByIdentifier delegates to storeService getByIdentifier`() = runTest {
        val store = mockk<Store>()
        coEvery { storeService.getByIdentifier("main") } returns store
        assertSame(store, controller.storeByIdentifier("main"))
    }

    @Test
    fun `stores delegates to storeService getByCompany`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Store>())
        coEvery { storeService.getByCompany(companyId) } returns expected
        assertEquals(expected, controller.stores(companyId))
    }

    // ---- providers ----

    @Test
    fun `paymentProviders delegates to providerService`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<PaymentProvider>())
        coEvery { providerService.getPaymentProvidersByCompany(companyId) } returns expected
        assertEquals(expected, controller.paymentProviders(companyId))
    }

    @Test
    fun `shippingProviders delegates to providerService`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<ShippingProvider>())
        coEvery { providerService.getShippingProvidersByCompany(companyId) } returns expected
        assertEquals(expected, controller.shippingProviders(companyId))
    }

    @Test
    fun `paymentProviderKeys gates and delegates`() = runTest {
        val expected = listOf("test")
        coEvery { providerService.paymentProviderKeys() } returns expected
        assertEquals(expected, controller.paymentProviderKeys(auth))
        verifyGate()
    }

    @Test
    fun `shippingProviderKeys gates and delegates`() = runTest {
        val expected = listOf("fixed")
        coEvery { providerService.shippingProviderKeys() } returns expected
        assertEquals(expected, controller.shippingProviderKeys(auth))
        verifyGate()
    }

    // ---- inventory / fulfillment (admin) ----

    @Test
    fun `inventory gates and reads by product`() = runTest {
        val productId = UUID.random()
        val expected = listOf(mockk<Inventory>())
        coEvery { inventoryService.getByProduct(productId) } returns expected
        assertEquals(expected, controller.inventory(auth, productId))
        verifyGate()
    }

    @Test
    fun `fulfillmentCenter gates and reads by id`() = runTest {
        val id = UUID.random()
        val center = mockk<FulfillmentCenter>()
        coEvery { fulfillmentService.getCenter(id) } returns center
        assertSame(center, controller.fulfillmentCenter(auth, id))
        verifyGate()
    }

    @Test
    fun `fulfillmentCenters gates and reads by company`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<FulfillmentCenter>())
        coEvery { fulfillmentService.getCentersByCompany(companyId) } returns expected
        assertEquals(expected, controller.fulfillmentCenters(auth, companyId))
        verifyGate()
    }

    // ---- carts (cart-scoped + admin) ----

    @Test
    fun `cart returns null when missing without an access check`() = runTest {
        val id = UUID.random()
        coEvery { cartService.get(id) } returns null
        assertNull(controller.cart(auth, id))
        coVerify(exactly = 0) { cartAccess.verify(any(), any()) }
    }

    @Test
    fun `cart verifies access then returns the cart`() = runTest {
        val id = UUID.random()
        val cart = sampleCart()
        coEvery { cartService.get(id) } returns cart
        assertSame(cart, controller.cart(auth, id))
        coVerify { cartAccess.verify(auth, cart) }
    }

    @Test
    fun `carts gates and pages by store`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(sampleCart())
        coEvery { cartService.getByStore(storeId, 0, 10) } returns expected
        assertEquals(expected, controller.carts(auth, storeId, 0, 10))
        verifyGate()
    }

    @Test
    fun `cartsByStatus gates and filters by status`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(sampleCart())
        coEvery { cartService.getByStoreAndStatus(storeId, CartStatusFlag.OPEN, 0, 10) } returns expected
        assertEquals(expected, controller.cartsByStatus(auth, storeId, CartStatusFlag.OPEN, 0, 10))
        verifyGate()
    }

    @Test
    fun `orders gates and reads paid carts by store`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(sampleCart())
        coEvery { cartService.getOrdersByStore(storeId, 0, 10) } returns expected
        assertEquals(expected, controller.orders(auth, storeId, 0, 10))
        verifyGate()
    }

    // ---- shipments / containers (admin) ----

    @Test
    fun `shipments gates and reads by cart`() = runTest {
        val cartId = UUID.random()
        val expected = listOf(mockk<Shipment>())
        coEvery { shipmentService.getByCart(cartId) } returns expected
        assertEquals(expected, controller.shipments(auth, cartId))
        verifyGate()
    }

    @Test
    fun `shipmentsByStore gates and reads by store and status`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(mockk<Shipment>())
        coEvery { shipmentService.getByStore(storeId, ShipmentStatus.SHIPPED, 0, 10) } returns expected
        assertEquals(expected, controller.shipmentsByStore(auth, storeId, ShipmentStatus.SHIPPED, 0, 10))
        verifyGate()
    }

    @Test
    fun `shipment gates and reads by id`() = runTest {
        val id = UUID.random()
        val shipment = mockk<Shipment>()
        coEvery { shipmentService.get(id) } returns shipment
        assertSame(shipment, controller.shipment(auth, id))
        verifyGate()
    }

    @Test
    fun `returns gates and reads by store with and without a status`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(mockk<bosca.ecommerce.model.Return>())
        coEvery { returnService.getByStore(storeId, bosca.ecommerce.model.ReturnStatus.REQUESTED, 0, 10) } returns expected
        coEvery { returnService.getByStore(storeId, null, 0, 10) } returns expected
        assertEquals(expected, controller.returns(auth, storeId, bosca.ecommerce.model.ReturnStatus.REQUESTED, 0, 10))
        assertEquals(expected, controller.returns(auth, storeId, null, 0, 10))
        verifyGate()
    }

    @Test
    fun `returnsByCart gates and reads by cart`() = runTest {
        val cartId = UUID.random()
        val expected = listOf(mockk<bosca.ecommerce.model.Return>())
        coEvery { returnService.getByCart(cartId) } returns expected
        assertEquals(expected, controller.returnsByCart(auth, cartId))
        verifyGate()
    }

    @Test
    fun `ecomReturn gates and reads by id`() = runTest {
        val id = UUID.random()
        val ret = mockk<bosca.ecommerce.model.Return>()
        coEvery { returnService.get(id) } returns ret
        assertSame(ret, controller.ecomReturn(auth, id))
        verifyGate()
    }

    @Test
    fun `container gates and reads by id`() = runTest {
        val id = UUID.random()
        val container = mockk<Container>()
        coEvery { containerService.get(id) } returns container
        assertSame(container, controller.container(auth, id))
        verifyGate()
    }

    @Test
    fun `containers gates and reads by company`() = runTest {
        val companyId = UUID.random()
        val expected = listOf(mockk<Container>())
        coEvery { containerService.getByCompany(companyId) } returns expected
        assertEquals(expected, controller.containers(auth, companyId))
        verifyGate()
    }

    // ---- promotions (admin) ----

    @Test
    fun `promotion gates and reads by id`() = runTest {
        val id = UUID.random()
        val promotion = mockk<Promotion>()
        coEvery { promotionService.get(id) } returns promotion
        assertSame(promotion, controller.promotion(auth, id))
        verifyGate()
    }

    @Test
    fun `promotions gates and pages by store`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(mockk<Promotion>())
        coEvery { promotionService.getByStore(storeId, 0, 10) } returns expected
        assertEquals(expected, controller.promotions(auth, storeId, 0, 10))
        verifyGate()
    }

    // ---- payments (admin) ----

    @Test
    fun `payment gates and reads by id`() = runTest {
        val id = UUID.random()
        val payment = mockk<Payment>()
        coEvery { paymentService.get(id) } returns payment
        assertSame(payment, controller.payment(auth, id))
        verifyGate()
    }

    @Test
    fun `payments gates and pages by account`() = runTest {
        val accountId = UUID.random()
        val expected = listOf(mockk<Payment>())
        coEvery { paymentService.getByAccount(accountId, 0, 10) } returns expected
        assertEquals(expected, controller.payments(auth, accountId, 0, 10))
        verifyGate()
    }

    // ---- audit (admin) ----

    @Test
    fun `audit gates and forwards filters`() = runTest {
        val entityId = UUID.random()
        val storeId = UUID.random()
        val expected = listOf(mockk<Audit>())
        coEvery {
            auditService.list("cart", entityId, storeId, "submit", 0, 50)
        } returns expected
        assertEquals(expected, controller.audit(auth, "cart", entityId, storeId, "submit", 0, 50))
        verifyGate()
    }

    // ---- subscription plans / subscriptions (admin) ----

    @Test
    fun `planGroup gates and reads by id`() = runTest {
        val id = UUID.random()
        val group = mockk<SubscriptionPlanGroup>()
        coEvery { subscriptionPlanService.getGroup(id) } returns group
        assertSame(group, controller.planGroup(auth, id))
        verifyGate()
    }

    @Test
    fun `planGroups gates and reads by store`() = runTest {
        val storeId = UUID.random()
        val expected = listOf(mockk<SubscriptionPlanGroup>())
        coEvery { subscriptionPlanService.getGroupsByStore(storeId) } returns expected
        assertEquals(expected, controller.planGroups(auth, storeId))
        verifyGate()
    }

    @Test
    fun `plan gates and reads by id`() = runTest {
        val id = UUID.random()
        val plan = mockk<SubscriptionPlan>()
        coEvery { subscriptionPlanService.getPlan(id) } returns plan
        assertSame(plan, controller.plan(auth, id))
        verifyGate()
    }

    @Test
    fun `plans gates and reads by group`() = runTest {
        val planGroupId = UUID.random()
        val expected = listOf(mockk<SubscriptionPlan>())
        coEvery { subscriptionPlanService.getPlansByGroup(planGroupId) } returns expected
        assertEquals(expected, controller.plans(auth, planGroupId))
        verifyGate()
    }

    @Test
    fun `subscription gates and reads by id`() = runTest {
        val id = UUID.random()
        val subscription = mockk<Subscription>()
        coEvery { subscriptionService.get(id) } returns subscription
        assertSame(subscription, controller.subscription(auth, id))
        verifyGate()
    }

    @Test
    fun `subscriptions gates and reads by account`() = runTest {
        val accountId = UUID.random()
        val expected = listOf(mockk<Subscription>())
        coEvery { subscriptionService.getByAccount(accountId, 0, 25) } returns expected
        assertEquals(expected, controller.subscriptions(auth, accountId, 0, 25))
        verifyGate()
    }

    // ---- shipping rates (cart-scoped) ----

    @Test
    fun `shippingRates verifies access then delegates to shippingService`() = runTest {
        val cartId = UUID.random()
        val cart = sampleCart()
        val expected = listOf(mockk<ShippingRate>())
        coEvery { cartService.get(cartId) } returns cart
        coEvery { shippingService.rates(cartId) } returns expected
        assertEquals(expected, controller.shippingRates(auth, cartId))
        coVerify { cartAccess.verify(auth, cart) }
    }

    @Test
    fun `shippingRates errors when the cart is not found`() = runTest {
        val cartId = UUID.random()
        coEvery { cartService.get(cartId) } returns null
        assertFailsWith<IllegalStateException> { controller.shippingRates(auth, cartId) }
        coVerify(exactly = 0) { cartAccess.verify(any(), any()) }
        coVerify(exactly = 0) { shippingService.rates(any()) }
    }

    private fun profile(id: UUID): Profile = mockk { every { this@mockk.id } returns id }

    private fun sampleCart(): Cart = Cart(
        id = UUID.random(),
        companyId = UUID.random(),
        storeId = UUID.random(),
        customerId = UUID.random(),
        expires = bosca.serialization.OffsetDateTime.now().plusSeconds(3600),
    )
}
