@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.cache.CacheManager
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AddCartItemInput
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryReservation
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.SubscriptionCartItemConfiguration
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.repository.CartRepository
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**cart orchestration — inventory reservation on add/remove, totals, and the expiration sweep. */
@OptIn(ExperimentalUuidApi::class)
class CartServiceImplTest {

    private val cartRepository = mockk<CartRepository>()
    private val cartAddressRepository = mockk<bosca.ecommerce.repository.CartAddressRepository>()
    private val paymentService = mockk<PaymentService>()
    private val subscriptionService = mockk<SubscriptionService>(relaxed = true)
    private val shipmentService = mockk<ShipmentService>(relaxed = true)
    private val companyService = mockk<CompanyService>()
    private val storeService = mockk<StoreService>()
    private val catalogService = mockk<CatalogService>()
    private val catalogProductService = mockk<CatalogProductService>()
    private val inventoryService = mockk<InventoryService>(relaxed = true)
    private val accountService = mockk<AccountService>(relaxed = true)
    private val customerService = mockk<CustomerService>()
    private val profileService = mockk<bosca.profile.profile.service.ProfileService>(relaxed = true)
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: CartServiceImpl

    private val storeId = UUID.random()
    private val companyId = UUID.random()
    private val cartId = UUID.random()
    private val catalogProductId = UUID.random()
    private val productId = UUID.random()
    private val inventoryId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        // The service builds batch-only ServiceCaches at construction (which resolve a CacheManager from DI).
        bosca.di.provides<CacheManager>(singleton = true) { io.mockk.mockk(relaxed = true) }
        coEvery { catalogService.get(any()) } returns bosca.ecommerce.model.Catalog(id = UUID.random(), companyId = companyId, key = "c", name = "C")
        service = CartServiceImpl(cartRepository, cartAddressRepository, companyService, storeService, catalogService, catalogProductService, inventoryService, paymentService, subscriptionService, shipmentService, accountService, customerService, profileService, auditService)
        // Submit requires a shipping address (physical) and a billing address (payment); default the
        // cart to having both so the submit tests exercise the path under test, not the address guard.
        coEvery { cartAddressRepository.getByCart(any()) } returns listOf(cartAddress(bosca.ecommerce.model.AddressType.SHIPPING), cartAddress(bosca.ecommerce.model.AddressType.BILLING))
    }

    private fun cartAddress(type: bosca.ecommerce.model.AddressType) = bosca.ecommerce.model.CartAddress(
        id = UUID.random(), cartId = cartId, type = type, firstName = "Ada", lastName = "Lovelace",
        address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
    )

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun store() = Store(
        id = storeId, identifier = "shop", name = "Shop", companyId = companyId, catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
        cartExpirationSeconds = 3600,
    )

    private fun catalogProduct() = CatalogProduct(
        id = catalogProductId, catalogId = UUID.random(), productId = productId, type = ProductType.PHYSICAL, price = Money.of("10.00"),
    )

    private fun openCart(items: List<CartItem> = emptyList()) = Cart(
        id = cartId, companyId = companyId, storeId = storeId, status = CartStatus.of(CartStatusFlag.OPEN),
        items = items, expires = OffsetDateTime.now().plusSeconds(3600),
    )

    private fun captureUpdate(): CapturingSlot<Cart> {
        val slot = slot<Cart>()
        coEvery { cartRepository.update(capture(slot)) } answers { slot.captured }
        return slot
    }

    @Test
    fun `create derives expiry from the store and audits`() = runTest {
        coEvery { storeService.get(storeId) } returns store()
        val added = slot<Cart>()
        coEvery { cartRepository.add(capture(added)) } answers { added.captured.copy(id = cartId) }

        val result = service.create(CartInput(storeId = storeId), principalId = null)

        assertEquals(companyId, added.captured.companyId)
        assertTrue(added.captured.expires.isAfter(OffsetDateTime.now().plusSeconds(3000)))
        assertEquals(cartId, result.id)
        coVerify(exactly = 1) {
            auditService.record<Cart>(eq("cart"), eq(cartId), eq("created"), any(), any(), any(), any(), any(), eq(storeId), any())
        }
    }

    @Test
    fun `create stamps the catalog currency and fails when the catalog is missing`() = runTest {
        coEvery { storeService.get(storeId) } returns store()
        val added = slot<Cart>()
        coEvery { cartRepository.add(capture(added)) } answers { added.captured.copy(id = cartId) }
        coEvery { catalogService.get(any()) } returns bosca.ecommerce.model.Catalog(id = UUID.random(), companyId = companyId, key = "c", name = "C", currency = "EUR")
        service.create(CartInput(storeId = storeId), principalId = null)
        assertEquals("EUR", added.captured.currency)

        coEvery { catalogService.get(any()) } returns null
        assertFailsWith<IllegalStateException> { service.create(CartInput(storeId = storeId), principalId = null) }
    }

    @Test
    fun `create copies the account's saved addresses onto the new cart`() = runTest {
        val accountId = UUID.random()
        coEvery { storeService.get(storeId) } returns store()
        coEvery { cartRepository.add(any()) } answers { firstArg<Cart>().copy(id = cartId) }
        coEvery { accountService.getAddresses(accountId) } returns listOf(
            bosca.ecommerce.model.AccountAddress(
                accountId = accountId, type = bosca.ecommerce.model.AddressType.SHIPPING, preferred = true,
                address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
            ),
        )
        coEvery { accountService.getCustomers(accountId) } returns listOf(
            bosca.ecommerce.model.Customer(id = UUID.random(), companyId = companyId, profileId = UUID.random()),
        )
        coEvery { profileService.getById(any()) } returns io.mockk.mockk(relaxed = true) { every { name } returns "Ada Lovelace" }
        val addr = slot<bosca.ecommerce.model.CartAddress>()
        coEvery { cartAddressRepository.upsert(capture(addr)) } answers { addr.captured.copy(id = UUID.random()) }

        service.create(CartInput(storeId = storeId, accountId = accountId), principalId = null)

        coVerify(exactly = 1) { cartAddressRepository.upsert(any()) }
        assertEquals(bosca.ecommerce.model.AddressType.SHIPPING, addr.captured.type)
        assertEquals("1 Main", addr.captured.address1)
        assertEquals("Ada", addr.captured.firstName)
        assertEquals("Lovelace", addr.captured.lastName)
    }

    @Test
    fun `create copies nothing when the account has no addresses`() = runTest {
        val accountId = UUID.random()
        coEvery { storeService.get(storeId) } returns store()
        coEvery { cartRepository.add(any()) } answers { firstArg<Cart>().copy(id = cartId) }
        coEvery { accountService.getAddresses(accountId) } returns emptyList()

        service.create(CartInput(storeId = storeId, accountId = accountId), principalId = null)

        coVerify(exactly = 0) { cartAddressRepository.upsert(any()) }
    }

    @Test
    fun `addItem reserves inventory, snapshots price, and recomputes totals`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = inventoryId, productId = productId, fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = 5),
        )
        val updated = captureUpdate()

        service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 2), principalId = null)

        coVerify(exactly = 1) { inventoryService.reserve(inventoryId, 2) }
        val cart = updated.captured
        assertEquals(1, cart.items.size)
        assertEquals(2, cart.quantity)
        assertEquals(Money.of("20.00"), cart.items.first().retailSubtotal)
        assertEquals(listOf(InventoryReservation(inventoryId, 2)), cart.items.first().reservations)
        assertEquals(Money.of("20.00"), cart.salesSubtotal)
    }

    @Test
    fun `addItem reserves nothing for an unstocked product`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { inventoryService.getByProduct(productId) } returns emptyList()
        val updated = captureUpdate()

        service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)

        coVerify(exactly = 0) { inventoryService.reserve(any(), any()) }
        assertTrue(updated.captured.items.first().reservations.isEmpty())
    }

    @Test
    fun `removeItem releases the line's holds`() = runTest {
        val itemId = UUID.random()
        val item = CartItem(id = itemId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 2, reservations = listOf(InventoryReservation(inventoryId, 2)))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        val updated = captureUpdate()

        service.removeItem(cartId, itemId, principalId = null)

        coVerify(exactly = 1) { inventoryService.releaseInCart(inventoryId, 2) }
        assertTrue(updated.captured.items.isEmpty())
    }

    @Test
    fun `submit commits holds and moves to pending plus payment due`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 2, reservations = listOf(InventoryReservation(inventoryId, 2)))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = null, principalId = null)

        coVerify(exactly = 1) { inventoryService.commitToPending(inventoryId, 2) }
        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PENDING))
        assertTrue(cart.status.has(CartStatusFlag.PAYMENT_DUE))
        assertTrue(!cart.status.has(CartStatusFlag.OPEN))
        assertEquals(cart.id, result.cart.id)
        assertTrue(result.payments.isEmpty())
    }

    @Test
    fun `submit with payment charges and moves to paid`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        val charged = bosca.ecommerce.model.Payment(id = UUID.random(), transactionType = bosca.ecommerce.model.TransactionType.PAYMENT, type = bosca.ecommerce.model.PaymentType.CREDIT_CARD, providerId = UUID.random(), storeId = storeId, amount = Money.of("10.00"), complete = true, confirmed = true)
        coEvery { paymentService.charge(any(), any()) } returns bosca.ecommerce.model.PaymentSubmitResult(charged)
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(token = "tok"), principalId = null)

        coVerify(exactly = 1) { paymentService.charge(any(), any()) }
        assertTrue(updated.captured.status.has(CartStatusFlag.PAID))
        assertTrue(!updated.captured.status.has(CartStatusFlag.PAYMENT_DUE))
        assertEquals(Money.of("10.00"), updated.captured.paid)
        assertEquals(1, result.payments.size)
    }

    @Test
    fun `submit with payment publishes a cart-paid event a consumer can observe`() = runTest {
        // Swap the relaxed PubSub for a recorder that captures exactly what a subscriber would receive.
        val recorder = RecordingPubSub()
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }

        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        val charged = bosca.ecommerce.model.Payment(id = UUID.random(), transactionType = bosca.ecommerce.model.TransactionType.PAYMENT, type = bosca.ecommerce.model.PaymentType.CREDIT_CARD, providerId = UUID.random(), storeId = storeId, amount = Money.of("10.00"), complete = true, confirmed = true)
        coEvery { paymentService.charge(any(), any()) } returns bosca.ecommerce.model.PaymentSubmitResult(charged)
        captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(token = "tok"), principalId = null)

        // The cart-paid event reached the PubSub channel with the right payload — what a consumer sees.
        val paid = recorder.published.filter { it.first == bosca.ecommerce.events.CART_PAID_CHANNEL }
        assertEquals(1, paid.size)
        val event = paid.first().second
        assertTrue(event is bosca.ecommerce.events.CartPaid)
        assertEquals(cartId, event.cartId)
        assertEquals(storeId, event.storeId)
        // Submitting also published the submitted event (paid implies submitted).
        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.CART_SUBMITTED_CHANNEL })
    }

    @Test
    fun `submit rejects an empty cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(emptyList())
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
    }

    @Test
    fun `submit rejects a physical cart with no shipping address`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(inventoryId, 1)))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        // Only a billing address on file — the physical line still has nowhere to ship.
        coEvery { cartAddressRepository.getByCart(cartId) } returns listOf(cartAddress(bosca.ecommerce.model.AddressType.BILLING))

        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    @Test
    fun `submit rejects a payment with no billing address`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        // Shipping is present, but a charge needs a billing address to attribute it.
        coEvery { cartAddressRepository.getByCart(cartId) } returns listOf(cartAddress(bosca.ecommerce.model.AddressType.SHIPPING))

        assertFailsWith<IllegalStateException> {
            service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(token = "tok"), principalId = null)
        }
        coVerify(exactly = 0) { paymentService.charge(any(), any()) }
    }

    @Test
    fun `submit allows a non-physical cart with no shipping address`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        // No addresses at all: a pay-later submit of a digital-only cart needs neither.
        coEvery { cartAddressRepository.getByCart(cartId) } returns emptyList()
        val updated = captureUpdate()

        service.submit(cartId, payment = null, principalId = null)

        assertTrue(updated.captured.status.has(CartStatusFlag.PENDING))
    }

    /** A cart with explicit financials + one physical line whose subtotal matches the total (so reprice is stable). */
    private fun cartWith(status: CartStatus, salesTotal: Money, due: Money, paid: Money = Money.ZERO, pendingPaid: Money = Money.ZERO) = Cart(
        id = cartId, companyId = companyId, storeId = storeId, status = status,
        items = listOf(CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, salesSubtotal = salesTotal)),
        salesTotal = salesTotal, due = due, paid = paid, pendingPaid = pendingPaid,
        expires = OffsetDateTime.now().plusSeconds(3600),
    )

    private fun mockCharge(amount: Money, confirmed: Boolean = true) {
        coEvery { paymentService.charge(any(), any()) } returns bosca.ecommerce.model.PaymentSubmitResult(
            bosca.ecommerce.model.Payment(
                id = UUID.random(), transactionType = bosca.ecommerce.model.TransactionType.PAYMENT,
                type = bosca.ecommerce.model.PaymentType.CASH, providerId = UUID.random(), storeId = storeId,
                amount = amount, complete = true, confirmed = confirmed,
            ),
        )
    }

    @Test
    fun `a partial payment leaves the cart payment-due and accumulates paid`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        mockCharge(Money.of("4.00"))
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("4.00"), type = bosca.ecommerce.model.PaymentType.CASH), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PENDING))
        assertTrue(cart.status.has(CartStatusFlag.PAYMENT_DUE))
        assertTrue(!cart.status.has(CartStatusFlag.PAID))
        assertEquals(Money.of("4.00"), cart.paid)
    }

    @Test
    fun `a second payment covering the balance moves a pending cart to paid`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE), salesTotal = Money.of("10.00"), due = Money.of("6.00"), paid = Money.of("4.00"),
        )
        mockCharge(Money.of("6.00"))
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("6.00"), type = bosca.ecommerce.model.PaymentType.CASH), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAID))
        assertTrue(!cart.status.has(CartStatusFlag.PAYMENT_DUE))
        assertEquals(Money.of("10.00"), cart.paid)
        // The cart was already committed (PENDING), so a further payment must not re-commit inventory.
        coVerify(exactly = 0) { inventoryService.commitToPending(any(), any()) }
    }

    @Test
    fun `paying an order in full splits shipments and marks the cart preparing`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        mockCharge(Money.of("10.00"))
        coEvery { shipmentService.createForPaidCart(any(), any()) } returns listOf(
            bosca.ecommerce.model.Shipment(id = UUID.random(), cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = UUID.random()),
        )
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = bosca.ecommerce.model.PaymentType.CASH), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAID))
        assertTrue(cart.status.has(CartStatusFlag.PREPARING))
        coVerify { shipmentService.createForPaidCart(any(), any()) }
    }

    @Test
    fun `a physical order with no shipments stays paid, not preparing or complete`() = runTest {
        // cartWith builds a PHYSICAL line; createForPaidCart returns nothing (untracked) → neither flag.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        mockCharge(Money.of("10.00"))
        coEvery { shipmentService.createForPaidCart(any(), any()) } returns emptyList()
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = bosca.ecommerce.model.PaymentType.CASH), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAID))
        assertTrue(!cart.status.has(CartStatusFlag.PREPARING))
        assertTrue(!cart.status.has(CartStatusFlag.COMPLETE))
    }

    @Test
    fun `an all-digital order completes at payment with nothing to ship`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns Cart(
            id = cartId, companyId = companyId, storeId = storeId,
            status = CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            items = listOf(CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))),
            salesTotal = Money.of("10.00"), due = Money.of("10.00"), expires = OffsetDateTime.now().plusSeconds(3600),
        )
        mockCharge(Money.of("10.00"))
        coEvery { shipmentService.createForPaidCart(any(), any()) } returns emptyList()
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = bosca.ecommerce.model.PaymentType.CASH), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAID))
        assertTrue(cart.status.has(CartStatusFlag.COMPLETE))
        assertTrue(!cart.status.has(CartStatusFlag.PREPARING))
    }

    private fun shipmentRow(status: ShipmentStatus) = bosca.ecommerce.model.Shipment(
        id = UUID.random(), cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = UUID.random(), status = status,
    )

    @Test
    fun `advanceFulfillment completes the order when every shipment has shipped`() = runTest {
        val recorder = RecordingPubSub()
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.PREPARING), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        coEvery { shipmentService.getByCart(cartId) } returns listOf(shipmentRow(ShipmentStatus.SHIPPED), shipmentRow(ShipmentStatus.SHIPPED))
        val updated = captureUpdate()

        service.advanceFulfillment(cartId, principalId = null)

        assertTrue(updated.captured.status.has(CartStatusFlag.SHIPPED))
        assertTrue(updated.captured.status.has(CartStatusFlag.COMPLETE))
        assertTrue(!updated.captured.status.has(CartStatusFlag.PREPARING))
        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.CART_COMPLETED_CHANNEL })
    }

    @Test
    fun `advanceFulfillment leaves the order shipping while a shipment is still awaiting`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.PREPARING), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        coEvery { shipmentService.getByCart(cartId) } returns listOf(shipmentRow(ShipmentStatus.SHIPPED), shipmentRow(ShipmentStatus.AWAITING))
        val updated = captureUpdate()

        service.advanceFulfillment(cartId, principalId = null)

        assertTrue(updated.captured.status.has(CartStatusFlag.SHIPPING))
        assertTrue(!updated.captured.status.has(CartStatusFlag.SHIPPED))
        assertTrue(!updated.captured.status.has(CartStatusFlag.COMPLETE))
    }

    @Test
    fun `advanceFulfillment is a no-op on an already-complete order`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.SHIPPED, CartStatusFlag.COMPLETE), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        val updated = captureUpdate()

        service.advanceFulfillment(cartId, principalId = null)

        assertTrue(!updated.isCaptured)
    }

    @Test
    fun `getOrdersByStore filters by the PAID status bit`() = runTest {
        val bit = slot<Int>()
        coEvery { cartRepository.getPaidByStore(storeId, capture(bit), 0, 50) } returns emptyList()
        service.getOrdersByStore(storeId, 0, 50)
        assertEquals(CartStatusFlag.PAID.bit, bit.captured)
    }

    @Test
    fun `an unconfirmed check accumulates pendingPaid yet still covers the cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        mockCharge(Money.of("10.00"), confirmed = false)
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = bosca.ecommerce.model.PaymentType.CHECK, checkNumber = "20123"), principalId = null)

        val cart = updated.captured
        assertEquals(Money.of("10.00"), cart.pendingPaid)
        assertEquals(Money.ZERO, cart.paid)
        assertTrue(cart.status.has(CartStatusFlag.PAID))
    }

    @Test
    fun `setAddress upserts the cart address and audits`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        captureUpdate() // setAddress reprices + saves the cart (tax/shipping recalc hook)
        val captured = slot<bosca.ecommerce.model.CartAddress>()
        coEvery { cartAddressRepository.upsert(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Analytical Way", city = "London", state = "LDN", country = "GB", zip = "EC1", phone = "555",
        )

        service.setAddress(cartId, input, principalId = null)

        assertEquals(bosca.ecommerce.model.AddressType.SHIPPING, captured.captured.type)
        assertEquals("Ada", captured.captured.firstName)
        coVerify(exactly = 1) {
            auditService.record<CartAddress>(eq("cart_address"), any(), eq("set"), any(), any(), any(), any(), any(), eq(storeId), any())
        }
    }

    @Test
    fun `setAddress works on a committed pending cart so it can gain its required shipping address`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.PENDING), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        captureUpdate()
        val captured = slot<bosca.ecommerce.model.CartAddress>()
        coEvery { cartAddressRepository.upsert(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Analytical Way", city = "London", state = "LDN", country = "GB", zip = "EC1", phone = "555",
        )

        service.setAddress(cartId, input, principalId = null)

        assertEquals(bosca.ecommerce.model.AddressType.SHIPPING, captured.captured.type)
    }

    @Test
    fun `setAddress is rejected once the cart is paid`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING) + CartStatusFlag.PAID,
            salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Analytical Way", city = "London", state = "LDN", country = "GB", zip = "EC1", phone = "555",
        )

        assertFailsWith<IllegalStateException> { service.setAddress(cartId, input, principalId = null) }
        coVerify(exactly = 0) { cartAddressRepository.upsert(any()) }
    }

    @Test
    fun `setShipping adds a shipping line and totals integrate tax plus shipping`() = runTest {
        // A taxed physical line (subtotal 20.00, tax 1.60) already in the cart.
        val taxed = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 2,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"),
            salesSubtotal = Money.of("20.00"), taxes = bosca.ecommerce.model.Tax.of(state = Money.of("1.60")),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(taxed))
        coEvery { storeService.get(storeId) } returns store()
        val updated = captureUpdate()
        val rate = bosca.ecommerce.model.ShippingRate(carrier = "USPS", serviceLevel = "Standard", token = "t", amount = Money.of("5.00"))

        service.setShipping(cartId, rate, listOf(rate), principalId = null)

        val cart = updated.captured
        assertTrue(cart.items.any { it.type == ProductType.SHIPPING && it.salesSubtotal == Money.of("5.00") })
        assertEquals(Money.of("5.00"), cart.shipping)
        assertEquals(Money.of("1.60"), cart.tax)
        // salesSubtotal = 20 (physical) + 5 (shipping); salesTotal = 25 - 0 discounts + 1.60 tax
        assertEquals(Money.of("25.00"), cart.salesSubtotal)
        assertEquals(Money.of("26.60"), cart.salesTotal)
    }

    @Test
    fun `expireCarts releases holds and flips to cancelled plus expired`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(inventoryId, 1)))
        val expiredCart = Cart(id = cartId, companyId = companyId, storeId = storeId, status = CartStatus.of(CartStatusFlag.OPEN), items = listOf(item), expires = OffsetDateTime.now().minusSeconds(60))
        // The sweep drains in batches: return the batch then empty (else the loop would spin).
        coEvery { cartRepository.getExpiredOpen(eq(CartStatusFlag.OPEN.bit), any()) } returns listOf(expiredCart) andThen emptyList()
        coEvery { cartRepository.getForUpdate(cartId) } returns expiredCart
        val updated = captureUpdate()

        val count = service.expireCarts()

        assertEquals(1, count)
        coVerify(exactly = 1) { inventoryService.releaseInCart(inventoryId, 1) }
        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.EXPIRED))
        assertTrue(cart.status.has(CartStatusFlag.CANCELLED))
        assertTrue(!cart.status.has(CartStatusFlag.OPEN))
        assertTrue(cart.items.isEmpty())
    }

    // --- admin order recourse: complete / cancel / refund (legacy parity) ---

    private fun paidCart(items: List<CartItem>, salesTotal: Money, paid: Money, status: CartStatus = CartStatus.of(CartStatusFlag.PAID)) = Cart(
        id = cartId, companyId = companyId, storeId = storeId, status = status,
        items = items, salesTotal = salesTotal, paid = paid, expires = OffsetDateTime.now().plusSeconds(3600),
    )

    private fun cartPayment(amount: Money) = Payment(
        id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD,
        providerId = UUID.random(), storeId = storeId, cartId = cartId, amount = amount, complete = true, confirmed = true,
    )

    @Test
    fun `complete finalizes a paid order, clears in-progress flags, and emits CartCompleted`() = runTest {
        val recorder = RecordingPubSub()
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.PENDING, CartStatusFlag.PREPARING), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val updated = captureUpdate()

        service.complete(cartId, principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.COMPLETE))
        assertTrue(!cart.status.has(CartStatusFlag.PENDING))
        assertTrue(!cart.status.has(CartStatusFlag.PREPARING))
        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.CART_COMPLETED_CHANNEL })
    }

    @Test
    fun `complete refuses an unpaid order`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        assertFailsWith<IllegalStateException> { service.complete(cartId, principalId = null) }
    }

    @Test
    fun `cancel flags the order cancelled and audits`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val updated = captureUpdate()

        service.cancel(cartId, principalId = null)

        assertTrue(updated.captured.status.has(CartStatusFlag.CANCELLED))
        coVerify(exactly = 1) {
            auditService.record<Cart>(eq("cart"), eq(cartId), eq("cancelled"), any(), any(), any(), any(), any(), eq(storeId), any())
        }
    }

    @Test
    fun `refundItems partially refunds one unit of a line, restocks it, and reduces paid`() = runTest {
        val itemId = UUID.random()
        val item = CartItem(
            id = itemId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 2,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"),
            salesSubtotal = Money.of("20.00"), reservations = listOf(InventoryReservation(inventoryId, 2)),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("20.00"), paid = Money.of("20.00"))
        val payment = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(itemId, 1)), RefundTender.ORIGINAL, checkNumber = null, reason = "damaged", principalId = null)

        // One unit restocked from pending, and exactly the one-unit price refunded to the original payment.
        coVerify(exactly = 1) { inventoryService.releasePending(inventoryId, 1) }
        coVerify(exactly = 1) { paymentService.refund(payment.id, Money.of("10.00"), "damaged", null) }
        val cart = updated.captured
        assertEquals(1, cart.items.first().quantity)
        assertEquals(1, cart.items.first().reservations.first().quantity)
        assertEquals(Money.of("10.00"), cart.paid)
        assertEquals(Money.ZERO, cart.refundDue)
        assertTrue(cart.status.has(CartStatusFlag.PAID))
    }

    @Test
    fun `refundItems batches across multiple lines in one refund`() = runTest {
        val a = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        val b = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 2, salesPrice = Money.of("5.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(a, b), salesTotal = Money.of("20.00"), paid = Money.of("20.00"))
        val payment = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        // Refund all of line a (10.00) + one unit of line b (5.00) = 15.00 in one operation.
        service.refundItems(cartId, listOf(RefundItemInput(a.id, 1), RefundItemInput(b.id, 1)), RefundTender.ORIGINAL, checkNumber = null, reason = null, principalId = null)

        coVerify(exactly = 1) { paymentService.refund(payment.id, Money.of("15.00"), null, null) }
        val cart = updated.captured
        assertEquals(1, cart.items.size) // line a fully removed, line b reduced to 1
        assertEquals(b.id, cart.items.first().id)
        assertEquals(1, cart.items.first().quantity)
        assertEquals(Money.of("5.00"), cart.paid)
    }

    @Test
    fun `refundItems on the whole order returns all money and marks it refunded`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        val payment = cartPayment(Money.of("10.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, checkNumber = null, reason = null, principalId = null)

        val cart = updated.captured
        assertTrue(cart.items.isEmpty())
        assertEquals(Money.ZERO, cart.paid)
        assertTrue(cart.status.has(CartStatusFlag.REFUNDED))
        assertTrue(!cart.status.has(CartStatusFlag.PAID))
    }

    @Test
    fun `refundItems to account credit uses the account-credit tender`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        val payment = cartPayment(Money.of("10.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refundToAccountCredit(any(), any(), any(), any()) } returns payment
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ACCOUNT_CREDIT, checkNumber = null, reason = null, principalId = null)

        coVerify(exactly = 1) { paymentService.refundToAccountCredit(payment.id, Money.of("10.00"), null, null) }
        coVerify(exactly = 0) { paymentService.refund(any(), any(), any(), any()) }
    }

    @Test
    fun `refundItems rejects an unpaid cart`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems rejects a quantity greater than the line`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        assertFailsWith<IllegalArgumentException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 2)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `confirmCheck settles a cleared check from pendingPaid into paid`() = runTest {
        val cart = paidCart(
            listOf(CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))),
            salesTotal = Money.of("10.00"), paid = Money.ZERO,
        ).copy(pendingPaid = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns cart
        val pid = UUID.random()
        coEvery { paymentService.confirmCheck(pid, any(), any()) } returns cartPayment(Money.of("10.00")).copy(id = pid, type = PaymentType.CHECK)
        val updated = captureUpdate()

        service.confirmCheck(cartId, pid, "20123", principalId = null)

        coVerify(exactly = 1) { paymentService.confirmCheck(pid, "20123", null) }
        val c = updated.captured
        assertEquals(Money.of("10.00"), c.paid)
        assertEquals(Money.ZERO, c.pendingPaid)
    }

    @Test
    fun `setLocked toggles the LOCKED flag`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        val updated = captureUpdate()
        service.setLocked(cartId, locked = true, principalId = null)
        assertTrue(updated.captured.status.has(CartStatusFlag.LOCKED))
    }

    @Test
    fun `setItemPrice overrides the line price, reprices, and surfaces a refund due`() = runTest {
        val itemId = UUID.random()
        val item = CartItem(
            id = itemId, catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 2,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"), salesSubtotal = Money.of("20.00"),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("20.00"), paid = Money.of("20.00"))
        val updated = captureUpdate()

        service.setItemPrice(cartId, itemId, retailPrice = Money.of("8.00"), salesPrice = Money.of("8.00"), principalId = null)

        val c = updated.captured
        assertEquals(Money.of("16.00"), c.items.first().salesSubtotal)
        assertEquals(Money.of("16.00"), c.salesTotal)
        assertEquals(Money.of("4.00"), c.refundDue) // paid 20 − salesTotal 16
        assertTrue(c.status.has(CartStatusFlag.REFUND_DUE))
    }

    @Test
    fun `removeAddress deletes the address of the given type and reprices`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        coEvery { cartAddressRepository.deleteByCartAndType(cartId, bosca.ecommerce.model.AddressType.BILLING) } returns Unit
        val updated = captureUpdate()

        service.removeAddress(cartId, bosca.ecommerce.model.AddressType.BILLING, principalId = null)

        coVerify(exactly = 1) { cartAddressRepository.deleteByCartAndType(cartId, bosca.ecommerce.model.AddressType.BILLING) }
        assertTrue(updated.isCaptured)
    }

    @Test
    fun `setBillingSameAsShipping true drops the billing address, sets the flag, and reprices`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { cartAddressRepository.deleteByCartAndType(cartId, bosca.ecommerce.model.AddressType.BILLING) } returns Unit
        val updated = captureUpdate()

        service.setBillingSameAsShipping(cartId, value = true, principalId = null)

        coVerify(exactly = 1) { cartAddressRepository.deleteByCartAndType(cartId, bosca.ecommerce.model.AddressType.BILLING) }
        assertTrue(updated.captured.billingSameAsShipping)
    }

    @Test
    fun `setBillingSameAsShipping false sets the flag without dropping billing`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart().copy(billingSameAsShipping = true)
        val updated = captureUpdate()

        service.setBillingSameAsShipping(cartId, value = false, principalId = null)

        coVerify(exactly = 0) { cartAddressRepository.deleteByCartAndType(any(), any()) }
        assertTrue(!updated.captured.billingSameAsShipping)
    }

    @Test
    fun `getByStoreAndStatus passes the requested status bit`() = runTest {
        val bit = slot<Int>()
        coEvery { cartRepository.getByStoreAndStatus(storeId, capture(bit), 0, 50) } returns emptyList()
        service.getByStoreAndStatus(storeId, CartStatusFlag.SHIPPED, 0, 50)
        assertEquals(CartStatusFlag.SHIPPED.bit, bit.captured)
    }

    // --- read delegators ---

    @Test
    fun `get delegates to the repository`() = runTest {
        val cart = openCart()
        coEvery { cartRepository.get(cartId) } returns cart
        assertEquals(cart, service.get(cartId))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val cart = openCart()
        coEvery { cartRepository.getByIds(listOf(cartId)) } returns listOf(cart)
        assertEquals(listOf(cart), service.getByIds(listOf(cartId)))
    }

    @Test
    fun `getByStore delegates to the repository`() = runTest {
        coEvery { cartRepository.getByStore(storeId, 5, 25) } returns listOf(openCart())
        assertEquals(1, service.getByStore(storeId, 5, 25).size)
    }

    // --- create error + address-copy edge paths ---

    @Test
    fun `create throws when the store is not found`() = runTest {
        coEvery { storeService.get(storeId) } returns null
        assertFailsWith<IllegalStateException> { service.create(CartInput(storeId = storeId), principalId = null) }
        coVerify(exactly = 0) { cartRepository.add(any()) }
    }

    @Test
    fun `create with an account but no customer leaves the recipient name blank`() = runTest {
        val accountId = UUID.random()
        coEvery { storeService.get(storeId) } returns store()
        coEvery { cartRepository.add(any()) } answers { firstArg<Cart>().copy(id = cartId) }
        coEvery { accountService.getAddresses(accountId) } returns listOf(
            bosca.ecommerce.model.AccountAddress(
                accountId = accountId, type = bosca.ecommerce.model.AddressType.BILLING, preferred = false,
                address1 = "9 Side", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
            ),
        )
        coEvery { accountService.getCustomers(accountId) } returns emptyList()
        val addr = slot<bosca.ecommerce.model.CartAddress>()
        coEvery { cartAddressRepository.upsert(capture(addr)) } answers { addr.captured.copy(id = UUID.random()) }

        service.create(CartInput(storeId = storeId, accountId = accountId), principalId = null)

        // No customer -> no profile lookup -> blank name on the copied address; first (non-preferred) used.
        assertEquals("", addr.captured.firstName)
        assertEquals("", addr.captured.lastName)
        assertEquals("9 Side", addr.captured.address1)
        coVerify(exactly = 0) { profileService.getById(any()) }
    }

    @Test
    fun `create tolerates a profile lookup that throws and copies a blank name`() = runTest {
        val accountId = UUID.random()
        coEvery { storeService.get(storeId) } returns store()
        coEvery { cartRepository.add(any()) } answers { firstArg<Cart>().copy(id = cartId) }
        coEvery { accountService.getAddresses(accountId) } returns listOf(
            bosca.ecommerce.model.AccountAddress(
                accountId = accountId, type = bosca.ecommerce.model.AddressType.SHIPPING, preferred = false,
                address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
            ),
        )
        coEvery { accountService.getCustomers(accountId) } returns listOf(
            bosca.ecommerce.model.Customer(id = UUID.random(), companyId = companyId, profileId = UUID.random()),
        )
        // runCatching swallows the profile failure -> name is empty rather than blowing up create.
        coEvery { profileService.getById(any()) } throws RuntimeException("profile service down")
        val addr = slot<bosca.ecommerce.model.CartAddress>()
        coEvery { cartAddressRepository.upsert(capture(addr)) } answers { addr.captured.copy(id = UUID.random()) }

        service.create(CartInput(storeId = storeId, accountId = accountId), principalId = null)

        assertEquals("", addr.captured.firstName)
        assertEquals("", addr.captured.lastName)
    }

    // --- addItem error / config / inventory edges ---

    @Test
    fun `addItem throws when the catalog product is not found`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns null
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)
        }
    }

    @Test
    fun `addItem rejects a non-positive quantity`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        assertFailsWith<IllegalArgumentException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 0), principalId = null)
        }
    }

    @Test
    fun `addItem rejects a catalog product priced in a different currency`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart() // USD cart
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        // The product's catalog is EUR -> folding a EUR price into the USD cart is a cross-currency error.
        coEvery { catalogService.get(any()) } returns bosca.ecommerce.model.Catalog(id = UUID.random(), companyId = companyId, key = "c", name = "C", currency = "EUR")
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)
        }
    }

    @Test
    fun `addItem throws when the product's catalog is missing`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { catalogService.get(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)
        }
    }

    @Test
    fun `addItem rejects when a closed cart is loaded for update`() = runTest {
        // openCartForUpdate requires OPEN; a PAID cart fails the status guard.
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)
        }
    }

    @Test
    fun `addItem throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1), principalId = null)
        }
    }

    @Test
    fun `addItem keeps the supplied line configuration`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { inventoryService.getByProduct(productId) } returns emptyList()
        val updated = captureUpdate()
        val config = bosca.ecommerce.model.SizeCartItemConfiguration(size = "L")

        service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 1, configuration = config), principalId = null)

        assertEquals(config, updated.captured.items.first().configuration)
    }

    @Test
    fun `addItem fails the oversell guard when inventory is short`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        // Only 1 unit available but 3 requested -> reserve() check(remaining <= 0) fails.
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = inventoryId, productId = productId, fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = 1),
        )
        assertFailsWith<IllegalStateException> {
            service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 3), principalId = null)
        }
    }

    @Test
    fun `addItem reserves greedily across inventory rows and skips empty ones`() = runTest {
        val secondInventory = UUID.random()
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        // First row has 2 (taken), a zero-available row is skipped, a third row covers the rest.
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = inventoryId, productId = productId, fulfillmentCenterId = UUID.random(), sku = "A", quantity = 2),
            Inventory(id = UUID.random(), productId = productId, fulfillmentCenterId = UUID.random(), sku = "B", quantity = 0),
            Inventory(id = secondInventory, productId = productId, fulfillmentCenterId = UUID.random(), sku = "C", quantity = 5),
        )
        val updated = captureUpdate()

        service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 3), principalId = null)

        coVerify(exactly = 1) { inventoryService.reserve(inventoryId, 2) }
        coVerify(exactly = 1) { inventoryService.reserve(secondInventory, 1) }
        assertEquals(2, updated.captured.items.first().reservations.size)
    }

    // --- updateItemQuantity (untested method) ---

    @Test
    fun `updateItemQuantity re-reserves and reprices the line`() = runTest {
        val itemId = UUID.random()
        val item = CartItem(
            id = itemId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"),
            salesSubtotal = Money.of("10.00"), reservations = listOf(InventoryReservation(inventoryId, 1)),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = inventoryId, productId = productId, fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = 9),
        )
        val updated = captureUpdate()

        service.updateItemQuantity(cartId, itemId, quantity = 3, principalId = null)

        // Old hold released, new hold of the new quantity taken, subtotal recomputed.
        coVerify(exactly = 1) { inventoryService.releaseInCart(inventoryId, 1) }
        coVerify(exactly = 1) { inventoryService.reserve(inventoryId, 3) }
        val line = updated.captured.items.first()
        assertEquals(3, line.quantity)
        assertEquals(Money.of("30.00"), line.salesSubtotal)
    }

    @Test
    fun `updateItemQuantity rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.updateItemQuantity(cartId, UUID.random(), 0, principalId = null) }
    }

    @Test
    fun `updateItemQuantity throws when the item is not in the cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        assertFailsWith<IllegalStateException> { service.updateItemQuantity(cartId, UUID.random(), 2, principalId = null) }
    }

    @Test
    fun `updateItemQuantity throws when the catalog product no longer resolves`() = runTest {
        val itemId = UUID.random()
        val item = CartItem(id = itemId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1)
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        coEvery { catalogProductService.get(catalogProductId) } returns null
        assertFailsWith<IllegalStateException> { service.updateItemQuantity(cartId, itemId, 2, principalId = null) }
    }

    // --- removeItem error path ---

    @Test
    fun `removeItem throws when the item is not in the cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        assertFailsWith<IllegalStateException> { service.removeItem(cartId, UUID.random(), principalId = null) }
    }

    // --- submit guards + failed-payment + subscription paths ---

    @Test
    fun `submit rejects a cart that is not payable`() = runTest {
        // A PAID cart fails payableCartForUpdate's already-paid guard.
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(
            listOf(CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))),
            salesTotal = Money.of("10.00"), paid = Money.of("10.00"),
        )
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
    }

    @Test
    fun `submit rejects a cancelled cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.CANCELLED), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
    }

    @Test
    fun `submit flags PAYMENT_FAILED when the charge does not complete`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        coEvery { paymentService.charge(any(), any()) } returns bosca.ecommerce.model.PaymentSubmitResult(
            bosca.ecommerce.model.Payment(
                id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD,
                providerId = UUID.random(), storeId = storeId, amount = Money.of("10.00"), complete = false,
            ),
        )
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(token = "tok"), principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAYMENT_FAILED))
        assertTrue(!cart.status.has(CartStatusFlag.PAID))
        assertEquals(Money.ZERO, cart.paid)
        assertEquals(1, result.payments.size)
    }

    @Test
    fun `submit caps a single payment at the remaining due`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        // The charge is asked for at most `due` even though the input offered more.
        val captured = slot<bosca.ecommerce.model.ChargePaymentInput>()
        coEvery { paymentService.charge(capture(captured), any()) } answers {
            bosca.ecommerce.model.PaymentSubmitResult(
                bosca.ecommerce.model.Payment(
                    id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CASH,
                    providerId = UUID.random(), storeId = storeId, amount = captured.captured.amount, complete = true, confirmed = true,
                ),
            )
        }
        captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("999.00"), type = PaymentType.CASH), principalId = null)

        assertEquals(Money.of("10.00"), captured.captured.amount)
        // Deterministic idempotency key per (cart, paid-so-far, charge amount): a double-clicked/retried submit
        // replays the same key and dedupes at the gateway; the charge carries the cart's snapshotted currency.
        assertEquals("cart:$cartId:${Money.ZERO}:${Money.of("10.00")}", captured.captured.idempotencyKey)
        assertEquals("USD", captured.captured.currency)
    }

    @Test
    fun `submit turns a paid subscription line into a subscription`() = runTest {
        val accountId = UUID.random()
        val planId = UUID.random()
        val subLine = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.SUBSCRIPTION, quantity = 1,
            salesSubtotal = Money.of("10.00"), configuration = SubscriptionCartItemConfiguration(planId = planId),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns Cart(
            id = cartId, companyId = companyId, storeId = storeId, accountId = accountId,
            status = CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            items = listOf(subLine), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
            expires = OffsetDateTime.now().plusSeconds(3600),
        )
        mockCharge(Money.of("10.00"))
        coEvery { shipmentService.createForPaidCart(any(), any()) } returns emptyList()
        coEvery { subscriptionService.createFromCart(any(), any(), any(), any(), any(), any(), any()) } returns subscription(accountId, planId)
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = PaymentType.CASH), principalId = null)

        coVerify(exactly = 1) { subscriptionService.createFromCart(storeId, accountId, planId, cartId, any(), any(), any()) }
        assertEquals(1, result.subscriptions.size)
        // A subscription order with no physical lines completes at payment.
        assertTrue(updated.captured.status.has(CartStatusFlag.COMPLETE))
    }

    @Test
    fun `submit fails a subscription checkout with no account on the cart`() = runTest {
        val planId = UUID.random()
        val subLine = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.SUBSCRIPTION, quantity = 1,
            salesSubtotal = Money.of("10.00"), configuration = SubscriptionCartItemConfiguration(planId = planId),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns Cart(
            id = cartId, companyId = companyId, storeId = storeId, accountId = null,
            status = CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            items = listOf(subLine), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
            expires = OffsetDateTime.now().plusSeconds(3600),
        )
        mockCharge(Money.of("10.00"))
        assertFailsWith<IllegalStateException> {
            service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = PaymentType.CASH), principalId = null)
        }
    }

    @Test
    fun `submit fails a subscription line with no plan selected`() = runTest {
        val accountId = UUID.random()
        val subLine = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.SUBSCRIPTION, quantity = 1,
            salesSubtotal = Money.of("10.00"), // EmptyCartItemConfiguration -> no planId
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns Cart(
            id = cartId, companyId = companyId, storeId = storeId, accountId = accountId,
            status = CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            items = listOf(subLine), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
            expires = OffsetDateTime.now().plusSeconds(3600),
        )
        mockCharge(Money.of("10.00"))
        assertFailsWith<IllegalStateException> {
            service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("10.00"), type = PaymentType.CASH), principalId = null)
        }
    }

    // --- setShipping store-missing error ---

    @Test
    fun `setShipping throws when the store is not found`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { storeService.get(storeId) } returns null
        val rate = bosca.ecommerce.model.ShippingRate(carrier = "USPS", serviceLevel = "Std", token = "t", amount = Money.of("5.00"))
        assertFailsWith<IllegalStateException> { service.setShipping(cartId, rate, listOf(rate), principalId = null) }
    }

    @Test
    fun `setShipping replaces an existing shipping line rather than stacking`() = runTest {
        val old = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.SHIPPING, quantity = 1, salesSubtotal = Money.of("3.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(old))
        coEvery { storeService.get(storeId) } returns store()
        val updated = captureUpdate()
        val rate = bosca.ecommerce.model.ShippingRate(carrier = "USPS", serviceLevel = "Std", token = "t", amount = Money.of("7.00"))

        service.setShipping(cartId, rate, listOf(rate), principalId = null)

        val shippingLines = updated.captured.items.filter { it.type == ProductType.SHIPPING }
        assertEquals(1, shippingLines.size)
        assertEquals(Money.of("7.00"), shippingLines.first().salesSubtotal)
    }

    // --- expireCarts / expireOne guards ---

    @Test
    fun `expireCarts returns zero when there are no expired carts`() = runTest {
        coEvery { cartRepository.getExpiredOpen(eq(CartStatusFlag.OPEN.bit), any()) } returns emptyList()
        assertEquals(0, service.expireCarts())
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    @Test
    fun `expireCarts skips a candidate that is no longer open`() = runTest {
        val candidate = openCart()
        coEvery { cartRepository.getExpiredOpen(eq(CartStatusFlag.OPEN.bit), any()) } returns listOf(candidate) andThen emptyList()
        // Re-locked under expireOne it is now PENDING (not OPEN) -> skipped, no update.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.PENDING), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        assertEquals(0, service.expireCarts())
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    @Test
    fun `expireCarts skips a candidate whose expiry is now in the future`() = runTest {
        val candidate = openCart()
        coEvery { cartRepository.getExpiredOpen(eq(CartStatusFlag.OPEN.bit), any()) } returns listOf(candidate) andThen emptyList()
        // Still OPEN but no longer expired (expires in the future) -> skipped.
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart().copy(expires = OffsetDateTime.now().plusSeconds(3600))
        assertEquals(0, service.expireCarts())
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    @Test
    fun `expireCarts skips a candidate that vanished before re-lock`() = runTest {
        val candidate = openCart()
        coEvery { cartRepository.getExpiredOpen(eq(CartStatusFlag.OPEN.bit), any()) } returns listOf(candidate) andThen emptyList()
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertEquals(0, service.expireCarts())
    }

    // --- advanceFulfillment early returns ---

    @Test
    fun `advanceFulfillment is a no-op when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        val updated = captureUpdate()
        service.advanceFulfillment(cartId, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    @Test
    fun `advanceFulfillment is a no-op when nothing has shipped yet`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.PREPARING), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        coEvery { shipmentService.getByCart(cartId) } returns listOf(shipmentRow(ShipmentStatus.AWAITING))
        val updated = captureUpdate()
        service.advanceFulfillment(cartId, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    @Test
    fun `advanceFulfillment completes when the only remaining shipment is cancelled`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.PREPARING), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        // One shipped (dispatched) + one cancelled -> allDone, order completes.
        coEvery { shipmentService.getByCart(cartId) } returns listOf(shipmentRow(ShipmentStatus.SHIPPED), shipmentRow(ShipmentStatus.CANCELLED))
        val updated = captureUpdate()
        service.advanceFulfillment(cartId, principalId = null)
        assertTrue(updated.captured.status.has(CartStatusFlag.COMPLETE))
    }

    @Test
    fun `advanceFulfillment is a no-op when the status already reflects the progress`() = runTest {
        // Already SHIPPING with a mix of shipped/awaiting -> recomputed status equals current -> no update.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.SHIPPING), salesTotal = Money.of("10.00"), due = Money.ZERO,
        )
        coEvery { shipmentService.getByCart(cartId) } returns listOf(shipmentRow(ShipmentStatus.SHIPPED), shipmentRow(ShipmentStatus.AWAITING))
        val updated = captureUpdate()
        service.advanceFulfillment(cartId, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    // --- complete error / idempotent paths ---

    @Test
    fun `complete throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.complete(cartId, principalId = null) }
    }

    @Test
    fun `complete refuses a cancelled order`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.CANCELLED), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        assertFailsWith<IllegalStateException> { service.complete(cartId, principalId = null) }
    }

    @Test
    fun `complete is idempotent on an already-complete order`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.COMPLETE), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val updated = captureUpdate()
        service.complete(cartId, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    // --- cancel error / idempotent paths ---

    @Test
    fun `cancel throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.cancel(cartId, principalId = null) }
    }

    @Test
    fun `cancel is idempotent on an already-cancelled order`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.CANCELLED), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val updated = captureUpdate()
        service.cancel(cartId, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    // --- refundItems error / tender / multi-payment edges ---

    @Test
    fun `refundItems rejects an empty item list`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.refundItems(cartId, emptyList(), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(UUID.random(), 1)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems rejects a cancelled cart`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(
            listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"),
            status = CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.CANCELLED),
        )
        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems throws when a requested item is not in the cart`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(UUID.random(), 1)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems to check uses the check tender and passes the check number`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        val payment = cartPayment(Money.of("10.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refundToCheck(any(), any(), any(), any(), any()) } returns payment
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.CHECK, checkNumber = "CK-9", reason = "rk", principalId = null)

        coVerify(exactly = 1) { paymentService.refundToCheck(payment.id, Money.of("10.00"), "CK-9", "rk", null) }
    }

    @Test
    fun `refundItems spreads a refund across payments oldest-first and skips exhausted ones`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("15.00"), salesSubtotal = Money.of("15.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("15.00"), paid = Money.of("15.00"))
        // First payment is fully refunded already (skipped), second is voided (filtered), third covers it.
        val exhausted = cartPayment(Money.of("5.00")).copy(refundedAmount = Money.of("5.00"))
        val voided = cartPayment(Money.of("20.00")).copy(voided = OffsetDateTime.now())
        val live = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(exhausted, voided, live)
        coEvery { paymentService.refund(any(), any(), any(), any()) } answers { firstArg<UUID>().let { live } }
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        // Only the live payment is hit, for the whole 15.00; the exhausted and voided rows are not.
        coVerify(exactly = 1) { paymentService.refund(live.id, Money.of("15.00"), null, null) }
        coVerify(exactly = 0) { paymentService.refund(exhausted.id, any(), any(), any()) }
        coVerify(exactly = 0) { paymentService.refund(voided.id, any(), any(), any()) }
    }

    @Test
    fun `refundItems skips refund rows and incomplete payments when spreading the refund`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("15.00"), salesSubtotal = Money.of("15.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("15.00"), paid = Money.of("15.00"))
        val refundRow = cartPayment(Money.of("20.00")).copy(transactionType = TransactionType.REFUND) // transactionType != PAYMENT -> filtered
        val incomplete = cartPayment(Money.of("20.00")).copy(complete = false)                         // not complete -> filtered
        val live = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(refundRow, incomplete, live)
        coEvery { paymentService.refund(any(), any(), any(), any()) } answers { live }
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        coVerify(exactly = 1) { paymentService.refund(live.id, Money.of("15.00"), null, null) }
        coVerify(exactly = 0) { paymentService.refund(refundRow.id, any(), any(), any()) }
        coVerify(exactly = 0) { paymentService.refund(incomplete.id, any(), any(), any()) }
    }

    @Test
    fun `refundItems skips a complete-but-unconfirmed (uncleared) tender`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        val unconfirmed = cartPayment(Money.of("20.00")).copy(confirmed = false) // e.g. a check awaiting clearing
        val live = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(unconfirmed, live)
        coEvery { paymentService.refund(any(), any(), any(), any()) } answers { live }
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        coVerify(exactly = 1) { paymentService.refund(live.id, Money.of("10.00"), null, null) }
        coVerify(exactly = 0) { paymentService.refund(unconfirmed.id, any(), any(), any()) } // never refund uncleared funds
    }

    @Test
    fun `setAddress throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "A", lastName = "B",
            address1 = "1", city = "C", state = "ST", country = "US", zip = "0", phone = "5",
        )
        assertFailsWith<IllegalStateException> { service.setAddress(cartId, input, principalId = null) }
    }

    @Test
    fun `refundItems fails when the cart payments cannot cover the refund`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        // The only payment is voided -> nothing refundable -> the final coverage check fails.
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(cartPayment(Money.of("10.00")).copy(voided = OffsetDateTime.now()))
        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems rejects a zero refund quantity`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 2, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("20.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("20.00"), paid = Money.of("20.00"))
        assertFailsWith<IllegalArgumentException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 0)), RefundTender.ORIGINAL, null, null, null)
        }
    }

    @Test
    fun `refundItems restocks across reservations leaving partial holds intact`() = runTest {
        val inv2 = UUID.random()
        val item = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 4,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("40.00"),
            salesSubtotal = Money.of("40.00"),
            reservations = listOf(InventoryReservation(inventoryId, 1), InventoryReservation(inv2, 3)),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("40.00"), paid = Money.of("40.00"))
        val payment = cartPayment(Money.of("40.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        // Refund 2 units: drains the 1-unit reservation entirely, then 1 of the 3-unit one (2 kept).
        service.refundItems(cartId, listOf(RefundItemInput(item.id, 2)), RefundTender.ORIGINAL, null, null, null)

        coVerify(exactly = 1) { inventoryService.releasePending(inventoryId, 1) }
        coVerify(exactly = 1) { inventoryService.releasePending(inv2, 1) }
        val line = updated.captured.items.first()
        assertEquals(2, line.quantity)
        assertEquals(listOf(InventoryReservation(inv2, 2)), line.reservations)
    }

    // --- confirmCheck error paths ---

    @Test
    fun `confirmCheck throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.confirmCheck(cartId, UUID.random(), null, principalId = null) }
    }

    @Test
    fun `confirmCheck rejects a payment that belongs to another cart`() = runTest {
        val cart = paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.ZERO).copy(pendingPaid = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns cart
        val pid = UUID.random()
        // confirmCheck returns a payment bound to a different cart -> require fails.
        coEvery { paymentService.confirmCheck(pid, any(), any()) } returns cartPayment(Money.of("10.00")).copy(id = pid, cartId = UUID.random())
        assertFailsWith<IllegalArgumentException> { service.confirmCheck(cartId, pid, null, principalId = null) }
    }

    @Test
    fun `confirmCheck floors pendingPaid at zero when the cleared amount exceeds it`() = runTest {
        // pendingPaid is only 4.00 but the cleared check is 10.00 -> coerceAtLeast keeps it at zero.
        val cart = paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.ZERO).copy(pendingPaid = Money.of("4.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns cart
        val pid = UUID.random()
        coEvery { paymentService.confirmCheck(pid, any(), any()) } returns cartPayment(Money.of("10.00")).copy(id = pid, type = PaymentType.CHECK)
        val updated = captureUpdate()

        service.confirmCheck(cartId, pid, null, principalId = null)

        assertEquals(Money.of("10.00"), updated.captured.paid)
        assertEquals(Money.ZERO, updated.captured.pendingPaid)
    }

    // --- setLocked unlock + idempotent ---

    @Test
    fun `setLocked unlock clears the LOCKED flag`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(
            emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"),
            status = CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.LOCKED),
        )
        val updated = captureUpdate()
        service.setLocked(cartId, locked = false, principalId = null)
        assertTrue(!updated.captured.status.has(CartStatusFlag.LOCKED))
    }

    @Test
    fun `setLocked is idempotent when the flag is already set`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(
            emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"),
            status = CartStatus.of(CartStatusFlag.PAID, CartStatusFlag.LOCKED),
        )
        val updated = captureUpdate()
        service.setLocked(cartId, locked = true, principalId = null)
        assertTrue(!updated.isCaptured)
    }

    @Test
    fun `setLocked throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.setLocked(cartId, locked = true, principalId = null) }
    }

    // --- setItemPrice error paths ---

    @Test
    fun `setItemPrice throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> {
            service.setItemPrice(cartId, UUID.random(), Money.of("1.00"), Money.of("1.00"), principalId = null)
        }
    }

    @Test
    fun `setItemPrice throws when the item is not in the cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(emptyList(), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        assertFailsWith<IllegalStateException> {
            service.setItemPrice(cartId, UUID.random(), Money.of("1.00"), Money.of("1.00"), principalId = null)
        }
    }

    // --- removeAddress error path ---

    @Test
    fun `removeAddress throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.removeAddress(cartId, bosca.ecommerce.model.AddressType.BILLING, principalId = null) }
    }

    // --- reprice(cartId) public overload ---

    @Test
    fun `reprice by id loads, reprices, and saves the cart`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        val updated = captureUpdate()
        service.reprice(cartId)
        assertTrue(updated.isCaptured)
    }

    @Test
    fun `reprice by id throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.reprice(cartId) }
    }

    @Test
    fun `save throws when the repository update returns null`() = runTest {
        // The save() helper errors when update() can't find the row (exercised through cancel).
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.PAID), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"))
        coEvery { cartRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.cancel(cartId, principalId = null) }
    }

    // --- submit: vault flag, unconfirmed-partial, subscription short-circuit, expired guard ---

    @Test
    fun `submit forces vaulting when the payment asks to save the method`() = runTest {
        // payment.save = true makes `save || subscriptionLines.isNotEmpty()` true on its first arm,
        // even without any subscription line, so the charge is asked to vault a reusable method.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        val captured = slot<bosca.ecommerce.model.ChargePaymentInput>()
        coEvery { paymentService.charge(capture(captured), any()) } answers {
            bosca.ecommerce.model.PaymentSubmitResult(
                bosca.ecommerce.model.Payment(
                    id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD,
                    providerId = UUID.random(), storeId = storeId, amount = captured.captured.amount, complete = true, confirmed = true,
                ),
            )
        }
        captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(token = "tok", save = true), principalId = null)

        assertTrue(captured.captured.save)
    }

    @Test
    fun `submit with an unconfirmed partial payment accrues pendingPaid and stays payment-due`() = runTest {
        // complete-but-unconfirmed (a held check) of less than the total: pendingPaid grows, paid stays
        // zero (the `else pendingPaid += ...` arm), and covered is false (the `+ PAYMENT_DUE` else arm).
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(CartStatus.of(CartStatusFlag.OPEN), salesTotal = Money.of("10.00"), due = Money.of("10.00"))
        mockCharge(Money.of("4.00"), confirmed = false)
        val updated = captureUpdate()

        service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("4.00"), type = PaymentType.CHECK, checkNumber = "20111"), principalId = null)

        val cart = updated.captured
        assertEquals(Money.of("4.00"), cart.pendingPaid)
        assertEquals(Money.ZERO, cart.paid)
        assertTrue(!cart.status.has(CartStatusFlag.PAID))
        assertTrue(cart.status.has(CartStatusFlag.PAYMENT_DUE))
    }

    @Test
    fun `submit does not create a subscription while the cart is only partially paid`() = runTest {
        // A subscription line, but the payment doesn't cover the total -> covered is false, so the
        // `if (covered && ...)` subscription block is short-circuited and no subscription is created.
        val accountId = UUID.random()
        val planId = UUID.random()
        val subLine = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.SUBSCRIPTION, quantity = 1,
            salesSubtotal = Money.of("10.00"), configuration = SubscriptionCartItemConfiguration(planId = planId),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns Cart(
            id = cartId, companyId = companyId, storeId = storeId, accountId = accountId,
            status = CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            items = listOf(subLine), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
            expires = OffsetDateTime.now().plusSeconds(3600),
        )
        mockCharge(Money.of("4.00"))
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = bosca.ecommerce.model.SubmitPaymentInput(amount = Money.of("4.00"), type = PaymentType.CASH), principalId = null)

        coVerify(exactly = 0) { subscriptionService.createFromCart(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(result.subscriptions.isEmpty())
        assertTrue(!updated.captured.status.has(CartStatusFlag.PAID))
    }

    @Test
    fun `submit rejects an expired cart`() = runTest {
        // payableCartForUpdate's `!CANCELLED && !EXPIRED` guard: an EXPIRED (but still PENDING) cart is closed.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.EXPIRED), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    @Test
    fun `submit throws when the cart does not exist`() = runTest {
        coEvery { cartRepository.getForUpdate(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
    }

    @Test
    fun `submit with no payment finalizes a cart already covered by prior tenders`() = runTest {
        // A PENDING cart whose prior tenders already cover the total, finalized by a no-payment submit:
        // the charge block is skipped (payment == null) yet `covered` is true, so it transitions to PAID.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAYMENT_DUE),
            salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        coEvery { shipmentService.createForPaidCart(any(), any()) } returns emptyList()
        val updated = captureUpdate()

        val result = service.submit(cartId, payment = null, principalId = null)

        val cart = updated.captured
        assertTrue(cart.status.has(CartStatusFlag.PAID))
        assertTrue(!cart.status.has(CartStatusFlag.PAYMENT_DUE))
        assertTrue(result.payments.isEmpty())
        coVerify(exactly = 0) { paymentService.charge(any(), any()) }
    }

    // --- refundItems: refund satisfied before iterating every payment (the break) ---

    @Test
    fun `refundItems stops refunding once the amount is covered, leaving later payments untouched`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1, salesPrice = Money.of("10.00"), salesSubtotal = Money.of("10.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("10.00"), paid = Money.of("10.00"))
        // Two live payments, each able to cover the whole 10.00 refund. Oldest-first, the first covers it
        // and the loop hits `if (remaining <= Money.ZERO) break` before touching the second.
        val first = cartPayment(Money.of("20.00"))
        val second = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(first, second)
        coEvery { paymentService.refund(any(), any(), any(), any()) } answers { first }
        captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        coVerify(exactly = 1) { paymentService.refund(first.id, Money.of("10.00"), null, null) }
        coVerify(exactly = 0) { paymentService.refund(second.id, any(), any(), any()) }
    }

    // --- multi-item line-map / filter else-arms (a sibling line left untouched by the mutation) ---

    @Test
    fun `updateItemQuantity leaves the cart's other lines untouched`() = runTest {
        val targetId = UUID.random()
        val otherId = UUID.random()
        val target = CartItem(
            id = targetId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"),
            salesSubtotal = Money.of("10.00"), reservations = listOf(InventoryReservation(inventoryId, 1)),
        )
        val other = CartItem(
            id = otherId, catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1,
            retailPrice = Money.of("3.00"), salesPrice = Money.of("3.00"), retailSubtotal = Money.of("3.00"), salesSubtotal = Money.of("3.00"),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(target, other))
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = inventoryId, productId = productId, fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = 9),
        )
        val updated = captureUpdate()

        service.updateItemQuantity(cartId, targetId, quantity = 3, principalId = null)

        // The untouched sibling line (the `else it` arm of the items.map) is preserved verbatim.
        val kept = updated.captured.items.first { it.id == otherId }
        assertEquals(other, kept)
        assertEquals(3, updated.captured.items.first { it.id == targetId }.quantity)
    }

    @Test
    fun `setItemPrice leaves the cart's other lines untouched`() = runTest {
        val targetId = UUID.random()
        val otherId = UUID.random()
        val target = CartItem(
            id = targetId, catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"), salesSubtotal = Money.of("10.00"),
        )
        val other = CartItem(
            id = otherId, catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 2,
            retailPrice = Money.of("4.00"), salesPrice = Money.of("4.00"), retailSubtotal = Money.of("8.00"), salesSubtotal = Money.of("8.00"),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(target, other), salesTotal = Money.of("18.00"), paid = Money.of("18.00"))
        val updated = captureUpdate()

        service.setItemPrice(cartId, targetId, retailPrice = Money.of("6.00"), salesPrice = Money.of("6.00"), principalId = null)

        // The sibling line (the `else it` arm) keeps its original prices/subtotals.
        val kept = updated.captured.items.first { it.id == otherId }
        assertEquals(other, kept)
        assertEquals(Money.of("6.00"), updated.captured.items.first { it.id == targetId }.salesSubtotal)
    }

    @Test
    fun `removeItem keeps the cart's other lines`() = runTest {
        val removeId = UUID.random()
        val keepId = UUID.random()
        val toRemove = CartItem(id = removeId, catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(inventoryId, 1)))
        val toKeep = CartItem(id = keepId, catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1, salesSubtotal = Money.of("5.00"))
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(toRemove, toKeep))
        val updated = captureUpdate()

        service.removeItem(cartId, removeId, principalId = null)

        // The non-matching line survives the filterNot (its keep-arm), only the target is dropped.
        assertEquals(listOf(keepId), updated.captured.items.map { it.id })
    }

    @Test
    fun `refundItems reduces one line and leaves an unrelated line fully intact`() = runTest {
        val refundedId = UUID.random()
        val untouchedId = UUID.random()
        val refunded = CartItem(
            id = refundedId, catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 2,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"), salesSubtotal = Money.of("20.00"),
        )
        val untouched = CartItem(
            id = untouchedId, catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1,
            retailPrice = Money.of("5.00"), salesPrice = Money.of("5.00"), retailSubtotal = Money.of("5.00"), salesSubtotal = Money.of("5.00"),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(refunded, untouched), salesTotal = Money.of("25.00"), paid = Money.of("25.00"))
        val payment = cartPayment(Money.of("25.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        // Refund 1 of the 2-unit line; the unrelated line is the `else it` arm of the remaining>0 map.
        service.refundItems(cartId, listOf(RefundItemInput(refundedId, 1)), RefundTender.ORIGINAL, checkNumber = null, reason = null, principalId = null)

        coVerify(exactly = 1) { paymentService.refund(payment.id, Money.of("10.00"), null, null) }
        val keptOther = updated.captured.items.first { it.id == untouchedId }
        assertEquals(untouched, keptOther)
        assertEquals(1, updated.captured.items.first { it.id == refundedId }.quantity)
    }

    @Test
    fun `refundItems keeps reservations already past the refunded quantity`() = runTest {
        // Two reservations [2, 5]; refund 1 unit: the first reservation covers it (release 1, 1 kept),
        // and the loop reaches the second with remaining already 0 -> the `if (remaining <= 0)` skip-arm
        // keeps it verbatim without any further release.
        val inv2 = UUID.random()
        val item = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 7,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("70.00"),
            salesSubtotal = Money.of("70.00"),
            reservations = listOf(InventoryReservation(inventoryId, 2), InventoryReservation(inv2, 5)),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("70.00"), paid = Money.of("70.00"))
        val payment = cartPayment(Money.of("70.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        // Only the first reservation gave up a unit; the second is kept untouched (the skip-arm).
        coVerify(exactly = 1) { inventoryService.releasePending(inventoryId, 1) }
        coVerify(exactly = 0) { inventoryService.releasePending(inv2, any()) }
        val line = updated.captured.items.first()
        assertEquals(6, line.quantity)
        assertEquals(listOf(InventoryReservation(inventoryId, 1), InventoryReservation(inv2, 5)), line.reservations)
    }

    // --- getAddresses read delegator ---

    @Test
    fun `getAddresses delegates to the address repository`() = runTest {
        val addresses = listOf(cartAddress(bosca.ecommerce.model.AddressType.SHIPPING))
        coEvery { cartAddressRepository.getByCart(cartId) } returns addresses
        assertEquals(addresses, service.getAddresses(cartId))
    }

    // --- reprice runs every registered CartPricer ---

    @Test
    fun `reprice runs each registered cart pricer in order before recomputing totals`() = runTest {
        // With a CartPricer registered, the reprice funnel's `pricer.price(priced)` body runs (it is a
        // no-op pipeline when none are registered). The pricer marks each line so we can observe it ran.
        val marker = Money.of("0.50")
        bosca.di.provides<CartPricer>(name = "test-pricer", singleton = true) {
            object : CartPricer {
                override val order: Int = 5
                override suspend fun price(cart: Cart): Cart =
                    cart.copy(items = cart.items.map { it.copy(discounts = it.discounts + marker) })
            }
        }
        val item = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 1,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("10.00"), salesSubtotal = Money.of("10.00"),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart(listOf(item))
        val updated = captureUpdate()

        service.reprice(cartId)

        // The pricer applied its discount and the recompute folded it into the cart totals.
        assertEquals(marker, updated.captured.items.first().discounts)
        assertEquals(marker, updated.captured.discounts)
        assertEquals(Money.of("9.50"), updated.captured.salesTotal)
    }

    // --- reserve: the `if (remaining <= 0) break` arm (a row remains after the quantity is satisfied) ---

    @Test
    fun `addItem stops reserving once the quantity is satisfied, leaving later rows untouched`() = runTest {
        val first = inventoryId
        val second = UUID.random()
        coEvery { cartRepository.getForUpdate(cartId) } returns openCart()
        coEvery { catalogProductService.get(catalogProductId) } returns catalogProduct()
        // The first row fully covers the 2 requested units; the loop then hits `remaining <= 0` and
        // breaks before drawing on the second row, so it is never reserved.
        coEvery { inventoryService.getByProduct(productId) } returns listOf(
            Inventory(id = first, productId = productId, fulfillmentCenterId = UUID.random(), sku = "A", quantity = 5),
            Inventory(id = second, productId = productId, fulfillmentCenterId = UUID.random(), sku = "B", quantity = 5),
        )
        val updated = captureUpdate()

        service.addItem(cartId, AddCartItemInput(catalogProductId = catalogProductId, quantity = 2), principalId = null)

        coVerify(exactly = 1) { inventoryService.reserve(first, 2) }
        coVerify(exactly = 0) { inventoryService.reserve(second, any()) }
        assertEquals(listOf(InventoryReservation(first, 2)), updated.captured.items.first().reservations)
    }

    // --- payableCartForUpdate: the `check(!has(PAID))` failure when a PENDING cart is also flagged PAID ---

    @Test
    fun `submit rejects an already-paid cart that is still pending`() = runTest {
        // OPEN/PENDING passes the first payable check, but the PAID flag fails `check(!has(PAID))`.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.PAID),
            salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        assertFailsWith<IllegalStateException> { service.submit(cartId, payment = null, principalId = null) }
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    // --- addressableCartForUpdate: the closed-cart guard (CANCELLED / EXPIRED) on an otherwise-editable cart ---

    @Test
    fun `setAddress is rejected on a cart that is neither open nor pending`() = runTest {
        // A PAID-only cart (no OPEN, no PENDING) fails the `(has(OPEN) || has(PENDING))` arm of the
        // first check, so addresses can no longer be edited.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PAID), salesTotal = Money.of("10.00"), due = Money.ZERO, paid = Money.of("10.00"),
        )
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        assertFailsWith<IllegalStateException> { service.setAddress(cartId, input, principalId = null) }
        coVerify(exactly = 0) { cartAddressRepository.upsert(any()) }
    }

    @Test
    fun `setAddress is rejected on a cancelled cart that is still pending`() = runTest {
        // PENDING && !PAID passes the first check; CANCELLED fails `!has(CANCELLED) && !has(EXPIRED)`.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.CANCELLED), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        assertFailsWith<IllegalStateException> { service.setAddress(cartId, input, principalId = null) }
        coVerify(exactly = 0) { cartAddressRepository.upsert(any()) }
    }

    @Test
    fun `setAddress is rejected on an expired cart that is still pending`() = runTest {
        // PENDING && !PAID passes the first check; EXPIRED fails the second arm of the closed-cart guard.
        coEvery { cartRepository.getForUpdate(cartId) } returns cartWith(
            CartStatus.of(CartStatusFlag.PENDING, CartStatusFlag.EXPIRED), salesTotal = Money.of("10.00"), due = Money.of("10.00"),
        )
        val input = bosca.ecommerce.model.SetCartAddressInput(
            type = bosca.ecommerce.model.AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        assertFailsWith<IllegalStateException> { service.setAddress(cartId, input, principalId = null) }
        coVerify(exactly = 0) { cartAddressRepository.upsert(any()) }
    }

    // --- releaseRefunded: the `if (release > 0)` false arm (a zero-quantity reservation releases nothing) ---

    @Test
    fun `refundItems releases nothing for a zero-quantity reservation then draws the next`() = runTest {
        val zeroInv = UUID.random()
        val realInv = UUID.random()
        val item = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.PHYSICAL, quantity = 2,
            retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"),
            salesSubtotal = Money.of("20.00"),
            // The leading reservation holds zero units: release = min(remaining, 0) = 0, so the
            // `if (release > 0)` arm is skipped and nothing is released from it; the next reservation
            // supplies the refunded unit.
            reservations = listOf(InventoryReservation(zeroInv, 0), InventoryReservation(realInv, 2)),
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.of("20.00"), paid = Money.of("20.00"))
        val payment = cartPayment(Money.of("20.00"))
        coEvery { paymentService.getAllByCart(cartId) } returns listOf(payment)
        coEvery { paymentService.refund(any(), any(), any(), any()) } returns payment
        val updated = captureUpdate()

        service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)

        coVerify(exactly = 0) { inventoryService.releasePending(zeroInv, any()) }
        coVerify(exactly = 1) { inventoryService.releasePending(realInv, 1) }
        // The zero-unit reservation is dropped (left = 0), the drawn one keeps its remainder.
        assertEquals(listOf(InventoryReservation(realInv, 1)), updated.captured.items.first().reservations)
    }

    // --- refundItems: the `check(refundAmount > Money.ZERO)` failure (reducing the line yields no refund) ---

    @Test
    fun `refundItems rejects a refund of a zero-priced line that yields no refund due`() = runTest {
        // The line is free (and nothing was actually paid), so reducing it does not lower salesTotal
        // below paid and refundDue stays zero — `check(refundAmount > Money.ZERO)` fails, nothing refunds.
        val item = CartItem(
            id = UUID.random(), catalogProductId = catalogProductId, type = ProductType.VIRTUAL, quantity = 2,
            retailPrice = Money.ZERO, salesPrice = Money.ZERO, retailSubtotal = Money.ZERO, salesSubtotal = Money.ZERO,
        )
        coEvery { cartRepository.getForUpdate(cartId) } returns paidCart(listOf(item), salesTotal = Money.ZERO, paid = Money.ZERO)

        assertFailsWith<IllegalStateException> {
            service.refundItems(cartId, listOf(RefundItemInput(item.id, 1)), RefundTender.ORIGINAL, null, null, null)
        }
        coVerify(exactly = 0) { paymentService.refund(any(), any(), any(), any()) }
        coVerify(exactly = 0) { cartRepository.update(any()) }
    }

    private fun subscription(accountId: UUID, planId: UUID) = bosca.ecommerce.model.Subscription(
        id = UUID.random(), storeId = storeId, accountId = accountId, planId = planId, planGroupId = UUID.random(),
        price = Money.of("10.00"), interval = 1, intervalUnit = bosca.ecommerce.model.IntervalUnit.MONTHS,
    )

    /** A PubSubService that records every publish — stands in for a subscribed consumer. */
    private class RecordingPubSub : bosca.pubsub.PubSubService {
        val published = mutableListOf<Pair<String, Any?>>()

        override suspend fun <T> publish(
            channel: String,
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            message: T,
        ) {
            published += channel to message
        }

        override fun <T> subscribe(
            channel: String,
            deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        ): kotlinx.coroutines.flow.Flow<bosca.pubsub.Message<T>> = kotlinx.coroutines.flow.emptyFlow()
    }

    // --- the DataLoader batch resolvers behind Cart.company / .store / .account / .customer ---

    private fun cart(
        companyId: UUID = UUID.random(),
        storeId: UUID = UUID.random(),
        accountId: UUID? = UUID.random(),
        customerId: UUID? = UUID.random(),
    ) = openCart().copy(id = UUID.random(), companyId = companyId, storeId = storeId, accountId = accountId, customerId = customerId)

    private fun company(id: UUID) = Company(id = id, organizationId = UUID.random(), profileId = UUID.random())
    private fun storeWith(id: UUID) = Store(
        id = id, identifier = "shop", name = "Shop", companyId = UUID.random(), catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )
    private fun account(id: UUID) = Account(id = id, companyId = UUID.random(), type = AccountType.CONSUMER)
    private fun customer(id: UUID) = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())

    @Test
    fun `loadCompaniesForCarts batches with O(1) loads and skips carts whose company is missing`() = runTest {
        val c1 = cart(); val c2 = cart(); val c3 = cart() // c3's company is absent
        val co1 = company(c1.companyId); val co2 = company(c2.companyId)
        coEvery { cartRepository.getByIds(any()) } returns listOf(c1, c2, c3)
        coEvery { companyService.getByIds(any()) } returns listOf(co1, co2) // no co3
        val batch = Batch<UUID, Company>(listOf(c1.id, c2.id, c3.id))

        service.loadCompaniesForCarts(listOf(c1.id, c2.id, c3.id), batch)

        assertEquals(co1, batch.getData(c1.id))
        assertEquals(co2, batch.getData(c2.id))
        assertNull(batch.getData(c3.id)) // missing company -> not set
        coVerify(exactly = 1) { cartRepository.getByIds(any()) }
        coVerify(exactly = 1) { companyService.getByIds(any()) }
    }

    @Test
    fun `loadStoresForCarts batches with O(1) loads and skips carts whose store is missing`() = runTest {
        val c1 = cart(); val c2 = cart(); val c3 = cart() // c3's store is absent
        val s1 = storeWith(c1.storeId); val s2 = storeWith(c2.storeId)
        coEvery { cartRepository.getByIds(any()) } returns listOf(c1, c2, c3)
        coEvery { storeService.getByIds(any()) } returns listOf(s1, s2) // no s3
        val batch = Batch<UUID, Store>(listOf(c1.id, c2.id, c3.id))

        service.loadStoresForCarts(listOf(c1.id, c2.id, c3.id), batch)

        assertEquals(s1, batch.getData(c1.id))
        assertEquals(s2, batch.getData(c2.id))
        assertNull(batch.getData(c3.id)) // missing store -> not set
        coVerify(exactly = 1) { cartRepository.getByIds(any()) }
        coVerify(exactly = 1) { storeService.getByIds(any()) }
    }

    @Test
    fun `loadAccountsForCarts batches, skips a missing account, and skips a cart with no account`() = runTest {
        // c1 has an account that resolves; c2 has an accountId whose account is absent; c3 has no accountId.
        val c1 = cart(); val c2 = cart(); val c3 = cart(accountId = null)
        val a1 = account(requireNotNull(c1.accountId))
        coEvery { cartRepository.getByIds(any()) } returns listOf(c1, c2, c3)
        coEvery { accountService.getByIds(any()) } returns listOf(a1) // no a2
        val batch = Batch<UUID, Account>(listOf(c1.id, c2.id, c3.id))

        service.loadAccountsForCarts(listOf(c1.id, c2.id, c3.id), batch)

        assertEquals(a1, batch.getData(c1.id))
        assertNull(batch.getData(c2.id)) // missing account -> not set
        assertNull(batch.getData(c3.id)) // no accountId -> not set
        coVerify(exactly = 1) { cartRepository.getByIds(any()) }
        coVerify(exactly = 1) { accountService.getByIds(any()) }
    }

    @Test
    fun `loadCustomersForCarts batches, skips a missing customer, and skips a cart with no customer`() = runTest {
        // c1 has a customer that resolves; c2 has a customerId whose customer is absent; c3 has no customerId.
        val c1 = cart(); val c2 = cart(); val c3 = cart(customerId = null)
        val cu1 = customer(requireNotNull(c1.customerId))
        coEvery { cartRepository.getByIds(any()) } returns listOf(c1, c2, c3)
        coEvery { customerService.getByIds(any()) } returns listOf(cu1) // no cu2
        val batch = Batch<UUID, Customer>(listOf(c1.id, c2.id, c3.id))

        service.loadCustomersForCarts(listOf(c1.id, c2.id, c3.id), batch)

        assertEquals(cu1, batch.getData(c1.id))
        assertNull(batch.getData(c2.id)) // missing customer -> not set
        assertNull(batch.getData(c3.id)) // no customerId -> not set
        coVerify(exactly = 1) { cartRepository.getByIds(any()) }
        coVerify(exactly = 1) { customerService.getByIds(any()) }
    }
}
