@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.Address
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Container
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryReservation
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLabel
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShipmentLine
import bosca.ecommerce.model.ShipmentParcel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.TrackingUpdate
import bosca.ecommerce.repository.ShipmentRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**a paid order is packed (per center, for density) into one shipment per box, then shipped. */
@OptIn(ExperimentalUuidApi::class)
class ShipmentServiceImplTest {

    private val shipmentRepository = mockk<ShipmentRepository>()
    private val inventoryService = mockk<InventoryService>(relaxed = true)
    private val containerService = mockk<ContainerService>(relaxed = true)
    private val cartService = mockk<CartService>(relaxed = true)
    private val catalogProductService = mockk<CatalogProductService>()
    private val productService = mockk<ProductService>()
    private val packer = DensityPacker() // real packer — pure logic
    private val fulfillmentService = mockk<FulfillmentService>()
    private val providerService = mockk<ProviderService>()
    private val companyService = mockk<CompanyService>(relaxed = true)
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ShipmentServiceImpl

    private val cartId = UUID.random()
    private val storeId = UUID.random()
    private val companyId = UUID.random()
    private val centerA = UUID.random()
    private val centerB = UUID.random()
    private val invA = UUID.random()
    private val invB = UUID.random()
    private val cpA = UUID.random()
    private val cpB = UUID.random()

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        // Container + Cart access goes through their services (cycles with ShipmentService -> provide<>()).
        bosca.di.provides<ContainerService>(singleton = true) { containerService }
        bosca.di.provides<CartService>(singleton = true) { cartService }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { shipmentRepository.getByCart(cartId) } returns emptyList()
        coEvery { inventoryService.get(invA) } returns
            Inventory(id = invA, productId = UUID.random(), fulfillmentCenterId = centerA, sku = "SKU-A", quantity = 50, pending = 10)
        coEvery { inventoryService.get(invB) } returns
            Inventory(id = invB, productId = UUID.random(), fulfillmentCenterId = centerB, sku = "SKU-B", quantity = 5, pending = 1)
        // Default: no container catalog (each center is UNABLE_TO_PACKAGE) and zero-dimension products.
        coEvery { containerService.getByCompany(companyId) } returns emptyList()
        coEvery { catalogProductService.get(any()) } answers { catalogProduct() }
        coEvery { productService.get(any()) } returns product(0.0)
        service = ShipmentServiceImpl(
            shipmentRepository, inventoryService, catalogProductService, productService, packer,
            fulfillmentService, providerService, companyService, auditService,
        )
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun cart(items: List<CartItem>) = Cart(
        id = cartId, companyId = companyId, storeId = storeId, items = items, expires = java.time.OffsetDateTime.now(),
    )

    private fun catalogProduct() = CatalogProduct(catalogId = UUID.random(), productId = UUID.random(), type = ProductType.PHYSICAL, price = Money.ZERO)
    private fun product(side: Double) = Product(
        companyId = companyId, manufacturerId = UUID.random(), manufacturerSku = "s", metadataId = UUID.random(),
        type = ProductType.PHYSICAL, weight = 0.1, width = side, height = side, length = side,
    )

    private fun captureAdds(): MutableList<Shipment> {
        val added = mutableListOf<Shipment>()
        coEvery { shipmentRepository.add(capture(added)) } answers { firstArg<Shipment>().copy(id = UUID.random()) }
        return added
    }

    @Test
    fun `splits a paid order per fulfillment center - with no containers each is UNABLE_TO_PACKAGE`() = runTest {
        val added = captureAdds()
        val result = service.createForPaidCart(
            cart(
                listOf(
                    CartItem(catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 2, reservations = listOf(InventoryReservation(invA, 2))),
                    CartItem(catalogProductId = cpB, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(invB, 1))),
                ),
            ),
            principalId = null,
        )
        assertEquals(2, result.size)
        assertEquals(setOf(centerA, centerB), added.map { it.fulfillmentCenterId }.toSet())
        // No containers → nothing boxes; the items are unpacked and the shipment is UNABLE_TO_PACKAGE (no loose box).
        assertTrue(added.all { it.status == ShipmentStatus.UNABLE_TO_PACKAGE })
        assertTrue(added.all { it.parcels.isEmpty() })
        val a = added.first { it.fulfillmentCenterId == centerA }
        assertEquals(1, a.unpacked.size)
        assertEquals(invA, a.unpacked.first().inventoryId)
        assertEquals("SKU-A", a.unpacked.first().sku)
        assertEquals(2, a.unpacked.first().quantity)
    }

    @Test
    fun `packs a center's items into multiple parcels of one shipment when a container fills`() = runTest {
        val containerId = UUID.random()
        // Container holds 1000 volume; 5x5x5 (125) items → 8 per box. 10 units → 2 boxes (parcels).
        coEvery { containerService.getByCompany(companyId) } returns listOf(
            Container(id = containerId, companyId = companyId, name = "S", width = 10.0, height = 10.0, length = 10.0, weight = 0.0,
                supportedWidth = 10.0, supportedHeight = 10.0, supportedLength = 10.0, supportedWeight = 1000.0),
        )
        coEvery { productService.get(any()) } returns product(5.0)
        val added = captureAdds()

        val result = service.createForPaidCart(
            cart(listOf(CartItem(catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 10, reservations = listOf(InventoryReservation(invA, 10))))),
            principalId = null,
        )

        // One shipment for the center, carrying 2 parcels (boxes), holding 10 units in total.
        assertEquals(1, result.size)
        assertEquals(1, added.size)
        assertEquals(centerA, added.single().fulfillmentCenterId)
        assertEquals(2, added.single().parcels.size)
        assertTrue(added.single().parcels.all { it.containerId == containerId })
        assertEquals(10, added.single().parcels.sumOf { p -> p.lines.sumOf { it.quantity } })
    }

    @Test
    fun `excludes subscription, virtual and shipping lines`() = runTest {
        val added = captureAdds()
        val result = service.createForPaidCart(
            cart(
                listOf(
                    CartItem(catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(invA, 1))),
                    CartItem(catalogProductId = UUID.random(), type = ProductType.SUBSCRIPTION, quantity = 1, reservations = listOf(InventoryReservation(invB, 1))),
                    CartItem(catalogProductId = UUID.random(), type = ProductType.SHIPPING, quantity = 1),
                    CartItem(catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1),
                ),
            ),
            principalId = null,
        )
        assertEquals(1, result.size)
        assertEquals(centerA, added.single().fulfillmentCenterId)
        // Only the physical line is in the shipment; with no containers it's unpacked (UNABLE_TO_PACKAGE).
        assertEquals(listOf(invA), added.single().unpacked.map { it.inventoryId })
    }

    @Test
    fun `is idempotent when the order is already split`() = runTest {
        coEvery { shipmentRepository.getByCart(cartId) } returns
            listOf(Shipment(id = UUID.random(), cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA))
        val result = service.createForPaidCart(
            cart(listOf(CartItem(catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(invA, 1))))),
            principalId = null,
        )
        assertEquals(1, result.size)
        coVerify(exactly = 0) { shipmentRepository.add(any()) }
    }

    @Test
    fun `an all-digital order produces no shipments`() = runTest {
        captureAdds()
        val result = service.createForPaidCart(
            cart(
                listOf(
                    CartItem(catalogProductId = UUID.random(), type = ProductType.VIRTUAL, quantity = 1),
                    CartItem(catalogProductId = UUID.random(), type = ProductType.SUBSCRIPTION, quantity = 1),
                ),
            ),
            principalId = null,
        )
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { shipmentRepository.add(any()) }
    }

    @Test
    fun `a physical line with no reservation is skipped, not shipped`() = runTest {
        captureAdds()
        val result = service.createForPaidCart(
            cart(listOf(CartItem(catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 1, reservations = emptyList()))),
            principalId = null,
        )
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { shipmentRepository.add(any()) }
    }

    @Test
    fun `ship draws down each line's inventory, marks SHIPPED with carrier and tracking`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.AWAITING,
            parcels = listOf(
                ShipmentParcel(
                    lines = listOf(
                        ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 2),
                        ShipmentLine(catalogProductId = cpB, inventoryId = invB, sku = "SKU-B", quantity = 1),
                    ),
                ),
            ),
        )
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        val result = service.ship(shipmentId, carrier = "UPS", tracking = "1Z999", principalId = null)

        coVerify { inventoryService.ship(invA, 2, null) }
        coVerify { inventoryService.ship(invB, 1, null) }
        assertEquals(ShipmentStatus.SHIPPED, updated.captured.status)
        assertEquals("UPS", updated.captured.carrier)
        assertEquals("1Z999", updated.captured.tracking)
        assertNotNull(updated.captured.shipped)
        assertEquals(ShipmentStatus.SHIPPED, result.status)
    }

    @Test
    fun `ship assigns a carrier from the center's shipping provider when none is supplied`() = runTest {
        bosca.di.provides<ShippingRateProvider>(name = "fixed-rate", singleton = true) { FixedRateShippingRateProvider() }
        val shipmentId = UUID.random()
        val spId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.AWAITING,
            parcels = listOf(ShipmentParcel(lines = listOf(ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 1)))),
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns FulfillmentCenter(
            id = centerA, companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = spId,
            address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
        )
        coEvery { providerService.getShippingProvider(spId) } returns ShippingProvider(
            id = spId, companyId = companyId, name = "USPS", key = "usps", providerKey = "fixed-rate",
        )
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.ship(shipmentId, carrier = null, tracking = null, principalId = null)

        // The carrier was assigned by the configured shipping provider, not supplied by the operator.
        assertEquals("USPS", updated.captured.carrier)
        assertEquals(ShipmentStatus.SHIPPED, updated.captured.status)
    }

    @Test
    fun `ship honors an operator-supplied tracking number with no carrier`() = runTest {
        // carrier is null but tracking is non-null: the `carrier != null || tracking != null` second arm
        // is taken, so the operator label is honored as-is and the center's provider is never consulted.
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns awaiting(shipmentId)
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.ship(shipmentId, carrier = null, tracking = "1Z-MANUAL", principalId = null)

        assertEquals("1Z-MANUAL", updated.captured.tracking)
        assertEquals(null, updated.captured.carrier)
        assertEquals(ShipmentStatus.SHIPPED, updated.captured.status)
        coVerify(exactly = 0) { fulfillmentService.getCenter(any()) }
    }

    @Test
    fun `ship rejects a shipment that is not awaiting`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        assertFailsWith<IllegalStateException> { service.ship(shipmentId, carrier = null, tracking = null, principalId = null) }
        coVerify(exactly = 0) { inventoryService.ship(any(), any(), any()) }
    }

    @Test
    fun `refreshTracking advances a shipped shipment along the carrier lifecycle`() = runTest {
        bosca.di.provides<ShippingRateProvider>(name = "fixed-rate", singleton = true) { FixedRateShippingRateProvider() }
        val shipmentId = UUID.random()
        val spId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns FulfillmentCenter(
            id = centerA, companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = spId,
            address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
        )
        coEvery { providerService.getShippingProvider(spId) } returns ShippingProvider(
            id = spId, companyId = companyId, name = "USPS", key = "usps", providerKey = "fixed-rate",
        )
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.refreshTracking(shipmentId, principalId = null)

        // The fixed-rate test provider simulates one step forward: SHIPPED -> IN_TRANSIT.
        assertEquals(ShipmentStatus.IN_TRANSIT, updated.captured.status)
        assertNotNull(updated.captured.carrierStatus)
    }

    @Test
    fun `repack boxes an unable-to-package shipment once a fitting container exists`() = runTest {
        val shipmentId = UUID.random()
        val containerId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.UNABLE_TO_PACKAGE,
            unpacked = listOf(ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 2)),
        )
        coEvery { containerService.getByCompany(companyId) } returns listOf(
            Container(id = containerId, companyId = companyId, name = "S", width = 10.0, height = 10.0, length = 10.0, weight = 0.0,
                supportedWidth = 10.0, supportedHeight = 10.0, supportedLength = 10.0, supportedWeight = 1000.0),
        )
        coEvery { productService.get(any()) } returns product(5.0)
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.repack(shipmentId, principalId = null)

        // Now packable: items move into the container, nothing unpacked, status flips to AWAITING.
        assertEquals(ShipmentStatus.AWAITING, updated.captured.status)
        assertTrue(updated.captured.unpacked.isEmpty())
        assertTrue(updated.captured.parcels.isNotEmpty() && updated.captured.parcels.all { it.containerId == containerId })
        assertEquals(2, updated.captured.parcels.sumOf { p -> p.lines.sumOf { it.quantity } })
    }

    @Test
    fun `repack leaves a shipment UNABLE_TO_PACKAGE when nothing still fits`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.UNABLE_TO_PACKAGE,
            unpacked = listOf(ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 1)),
        )
        coEvery { containerService.getByCompany(companyId) } returns emptyList() // still no containers
        coEvery { productService.get(any()) } returns product(5.0)
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.repack(shipmentId, principalId = null)

        assertEquals(ShipmentStatus.UNABLE_TO_PACKAGE, updated.captured.status)
        assertTrue(updated.captured.parcels.isEmpty())
        assertEquals(1, updated.captured.unpacked.size)
    }

    @Test
    fun `repack rejects a shipment that is not awaiting`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        assertFailsWith<IllegalStateException> { service.repack(shipmentId, principalId = null) }
    }

    @Test
    fun `refreshTracking is a no-op on a shipment that is not in flight`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.AWAITING,
        )
        val result = service.refreshTracking(shipmentId, principalId = null)
        assertEquals(ShipmentStatus.AWAITING, result.status)
        coVerify(exactly = 0) { shipmentRepository.update(any()) }
    }

    @Test
    fun `refreshTracking is a no-op on a dispatched but terminal shipment`() = runTest {
        // DELIVERED is dispatched (so `!isDispatched` is false) but terminal -> the `|| isTerminal` arm
        // short-circuits to a no-op; a delivered shipment never re-polls the carrier.
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.DELIVERED,
        )
        val result = service.refreshTracking(shipmentId, principalId = null)
        assertEquals(ShipmentStatus.DELIVERED, result.status)
        coVerify(exactly = 0) { shipmentRepository.update(any()) }
        coVerify(exactly = 0) { fulfillmentService.getCenter(any()) }
    }

    @Test
    fun `ship resolves the origin, destination, and box parcels for the carrier`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        val containerId = UUID.random()
        val capturedDestination = slot<Address>()
        val capturedParcels = slot<List<Parcel>>()
        bosca.di.provides<ShippingRateProvider>(name = "capture", singleton = true) {
            mockk { coEvery { purchaseLabel(any(), any(), any(), capture(capturedDestination), capture(capturedParcels)) } returns ShipmentLabel(carrier = "X", tracking = "T") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING,
            parcels = listOf(ShipmentParcel(containerId = containerId, lines = listOf(ShipmentLine(cpA, invA, "SKU-A", 1)))),
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "capture")
        coEvery { containerService.get(containerId) } returns Container(
            id = containerId, companyId = companyId, name = "B", width = 10.0, height = 10.0, length = 10.0, weight = 0.5,
            supportedWidth = 10.0, supportedHeight = 10.0, supportedLength = 10.0, supportedWeight = 100.0,
        )
        coEvery { cartService.getAddresses(cartId) } returns listOf(
            CartAddress(cartId = cartId, type = AddressType.SHIPPING, firstName = "A", lastName = "B", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5"),
        )
        coEvery { companyService.get(companyId) } returns Company(organizationId = UUID.random(), profileId = UUID.random())
        coEvery { productService.get(any()) } returns product(2.0)
        coEvery { shipmentRepository.update(any()) } answers { firstArg() }

        val result = service.ship(shipmentId, carrier = null, tracking = null, principalId = null)

        assertEquals("X", result.carrier)
        assertTrue(capturedParcels.captured.isNotEmpty())
        assertNotNull(capturedDestination.captured)
    }

    @Test
    fun `ship tolerates a missing destination, null company, and an uncontainered parcel`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "capture2", singleton = true) {
            mockk { coEvery { purchaseLabel(any(), any(), any(), any(), any()) } returns ShipmentLabel(carrier = "Y", tracking = "T2") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING,
            parcels = listOf(ShipmentParcel(containerId = null, lines = listOf(ShipmentLine(cpA, invA, "SKU-A", 1)))), // no container -> skipped
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "capture2")
        coEvery { cartService.getAddresses(cartId) } returns emptyList() // no shipping address -> destination null
        coEvery { companyService.get(companyId) } returns null           // default units
        coEvery { shipmentRepository.update(any()) } answers { firstArg() }

        assertEquals("Y", service.ship(shipmentId, carrier = null, tracking = null, principalId = null).carrier)
    }

    @Test
    fun `ship tolerates a billing-only address and a container that cannot be resolved`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        val containerId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "capture3", singleton = true) {
            mockk { coEvery { purchaseLabel(any(), any(), any(), any(), any()) } returns ShipmentLabel(carrier = "Z", tracking = "T3") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING,
            parcels = listOf(ShipmentParcel(containerId = containerId, lines = listOf(ShipmentLine(cpA, invA, "SKU-A", 1)))),
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "capture3")
        coEvery { cartService.getAddresses(cartId) } returns listOf( // BILLING only -> the SHIPPING predicate evaluates false
            CartAddress(cartId = cartId, type = AddressType.BILLING, firstName = "A", lastName = "B", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5"),
        )
        coEvery { containerService.get(containerId) } returns null // container missing -> parcel skipped
        coEvery { companyService.get(companyId) } returns Company(organizationId = UUID.random(), profileId = UUID.random())
        coEvery { shipmentRepository.update(any()) } answers { firstArg() }

        assertEquals("Z", service.ship(shipmentId, carrier = null, tracking = null, principalId = null).carrier)
    }

    @Test
    fun `ship weighs a parcel line as zero when its catalog product or product is unresolved`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        val containerId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "capture4", singleton = true) {
            mockk { coEvery { purchaseLabel(any(), any(), any(), any(), any()) } returns ShipmentLabel(carrier = "W", tracking = "T4") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING,
            parcels = listOf(
                ShipmentParcel(
                    containerId = containerId,
                    lines = listOf(
                        ShipmentLine(cpA, invA, "SKU-A", 1), // catalog product missing -> lineWeight returns 0
                        ShipmentLine(cpB, invB, "SKU-B", 1), // catalog product present but product missing -> lineWeight returns 0
                    ),
                ),
            ),
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "capture4")
        coEvery { containerService.get(containerId) } returns Container(
            id = containerId, companyId = companyId, name = "B", width = 10.0, height = 10.0, length = 10.0, weight = 0.5,
            supportedWidth = 10.0, supportedHeight = 10.0, supportedLength = 10.0, supportedWeight = 100.0,
        )
        coEvery { cartService.getAddresses(cartId) } returns emptyList()
        coEvery { companyService.get(companyId) } returns null
        coEvery { catalogProductService.get(cpA) } returns null              // L199: catalog product null
        coEvery { catalogProductService.get(cpB) } returns catalogProduct()
        coEvery { productService.get(any()) } returns null                   // L200: product null
        coEvery { shipmentRepository.update(any()) } answers { firstArg() }

        assertEquals("W", service.ship(shipmentId, carrier = null, tracking = null, principalId = null).carrier)
    }

    private fun awaiting(id: UUID, center: UUID = centerA) = Shipment(
        id = id, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = center,
        status = ShipmentStatus.AWAITING,
        parcels = listOf(ShipmentParcel(lines = listOf(ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 1)))),
    )

    private fun center(spId: UUID, id: UUID = centerA) = FulfillmentCenter(
        id = id, companyId = companyId, name = "DC", connectorKey = "manual", shippingProviderId = spId,
        address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
    )

    private fun shippingProvider(spId: UUID, key: String) = ShippingProvider(id = spId, companyId = companyId, name = "P", key = "p", providerKey = key)

    @Test
    fun `get and getByCart delegate to the repository`() = runTest {
        val s = awaiting(UUID.random())
        coEvery { shipmentRepository.get(s.id) } returns s
        coEvery { shipmentRepository.getByCart(cartId) } returns listOf(s)
        assertEquals(s.id, service.get(s.id)?.id)
        assertEquals(1, service.getByCart(cartId).size)
    }

    @Test
    fun `getByStore returns the whole store when no status filter is given`() = runTest {
        coEvery { shipmentRepository.getByStore(storeId, 0, 50) } returns listOf(awaiting(UUID.random()))
        assertEquals(1, service.getByStore(storeId, status = null, offset = 0, limit = 50).size)
    }

    @Test
    fun `getByStore applies the lowercased status filter`() = runTest {
        coEvery { shipmentRepository.getByStoreAndStatus(storeId, "shipped", 0, 50) } returns emptyList()
        assertTrue(service.getByStore(storeId, status = ShipmentStatus.SHIPPED, offset = 0, limit = 50).isEmpty())
        coVerify(exactly = 1) { shipmentRepository.getByStoreAndStatus(storeId, "shipped", 0, 50) }
    }

    @Test
    fun `ship ships unlabeled when the center has no shipping provider`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns awaiting(shipmentId)
        coEvery { fulfillmentService.getCenter(centerA) } returns null // resolveLabel returns null
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.ship(shipmentId, carrier = null, tracking = null, principalId = null)

        assertEquals(ShipmentStatus.SHIPPED, updated.captured.status)
        assertEquals(null, updated.captured.carrier)
    }

    @Test
    fun `ship ships unlabeled when the provider config is absent`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns awaiting(shipmentId)
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns null // resolveLabel returns null
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.ship(shipmentId, carrier = null, tracking = null, principalId = null)

        assertEquals(null, updated.captured.carrier)
    }

    @Test
    fun `ship ships unlabeled when the provider throws while assigning a label`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "boom", singleton = true) {
            mockk { coEvery { purchaseLabel(any(), any(), any(), any(), any()) } throws RuntimeException("carrier down") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns awaiting(shipmentId)
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "boom")
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.ship(shipmentId, carrier = null, tracking = null, principalId = null)

        assertEquals(ShipmentStatus.SHIPPED, updated.captured.status)
        assertEquals(null, updated.captured.carrier)
    }

    @Test
    fun `repack is a no-op on a shipment with nothing to pack`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.AWAITING, // no parcels, no unpacked
        )
        val result = service.repack(shipmentId, principalId = null)
        assertEquals(ShipmentStatus.AWAITING, result.status)
        coVerify(exactly = 0) { shipmentRepository.update(any()) }
    }

    @Test
    fun `repackUnpackable re-packs every blocked shipment for the company`() = runTest {
        val s1 = UUID.random()
        val s2 = UUID.random()
        coEvery { shipmentRepository.getUnpackableByCompany(companyId) } returns listOf(
            Shipment(id = s1, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.UNABLE_TO_PACKAGE, unpacked = listOf(ShipmentLine(cpA, invA, "SKU-A", 1))),
            Shipment(id = s2, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.UNABLE_TO_PACKAGE, unpacked = listOf(ShipmentLine(cpA, invA, "SKU-A", 1))),
        )
        coEvery { shipmentRepository.getForUpdate(s1) } returns Shipment(id = s1, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.UNABLE_TO_PACKAGE, unpacked = listOf(ShipmentLine(cpA, invA, "SKU-A", 1)))
        coEvery { shipmentRepository.getForUpdate(s2) } returns Shipment(id = s2, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.UNABLE_TO_PACKAGE, unpacked = listOf(ShipmentLine(cpA, invA, "SKU-A", 1)))
        coEvery { shipmentRepository.update(any()) } answers { firstArg() }
        coEvery { productService.get(any()) } returns product(5.0)

        val count = service.repackUnpackable(companyId, principalId = null)

        assertEquals(2, count)
        coVerify(exactly = 2) { shipmentRepository.update(any()) }
    }

    @Test
    fun `refreshTracking is a no-op when the carrier returns no reading`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns null // resolveTracking returns null

        service.refreshTracking(shipmentId, principalId = null)

        coVerify(exactly = 0) { shipmentRepository.update(any()) }
    }

    @Test
    fun `refreshTracking does not regress when the reading is behind the current status`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "stale", singleton = true) {
            mockk { coEvery { track(any(), any()) } returns TrackingUpdate(status = ShipmentStatus.SHIPPED, carrierStatus = "old") }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.IN_TRANSIT, // already ahead of the SHIPPED reading
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "stale")

        service.refreshTracking(shipmentId, principalId = null)

        coVerify(exactly = 0) { shipmentRepository.update(any()) }
    }

    @Test
    fun `sweepTracking polls each in-flight shipment`() = runTest {
        val s1 = UUID.random()
        val s2 = UUID.random()
        coEvery { shipmentRepository.getInFlight(any()) } returns listOf(
            Shipment(id = s1, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING),
            Shipment(id = s2, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING),
        )
        // Each refreshTracking re-locks the row; AWAITING short-circuits the advance (not in flight).
        coEvery { shipmentRepository.getForUpdate(s1) } returns Shipment(id = s1, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING)
        coEvery { shipmentRepository.getForUpdate(s2) } returns Shipment(id = s2, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA, status = ShipmentStatus.AWAITING)

        assertEquals(2, service.sweepTracking())
    }

    @Test
    fun `createForPaidCart skips a reservation whose inventory row is missing`() = runTest {
        val missingInv = UUID.random()
        val item = CartItem(id = UUID.random(), catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(missingInv, 1)))
        coEvery { inventoryService.get(missingInv) } returns null // referenced row is gone
        val added = captureAdds()

        val shipments = service.createForPaidCart(cart(listOf(item)), principalId = null)

        // No center could be resolved for the line, so no shipment is created.
        assertTrue(shipments.isEmpty())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `createForPaidCart packs with unknown dimensions when the product cannot be resolved`() = runTest {
        val item = CartItem(id = UUID.random(), catalogProductId = cpA, type = ProductType.PHYSICAL, quantity = 1, reservations = listOf(InventoryReservation(invA, 1)))
        coEvery { catalogProductService.get(cpA) } returns null // product unresolved -> zero dims, still packed
        val added = captureAdds()

        val shipments = service.createForPaidCart(cart(listOf(item)), principalId = null)

        assertEquals(1, shipments.size)
        assertEquals(1, added.size)
    }

    // --- ship: not-found and update-null error paths ---

    @Test
    fun `ship throws when the shipment does not exist`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns null
        assertFailsWith<IllegalStateException> { service.ship(shipmentId, carrier = "UPS", tracking = "1Z", principalId = null) }
        coVerify(exactly = 0) { inventoryService.ship(any(), any(), any()) }
    }

    @Test
    fun `ship throws when the row vanished before the update`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns awaiting(shipmentId)
        coEvery { shipmentRepository.update(any()) } returns null // row gone between lock and write
        assertFailsWith<IllegalStateException> { service.ship(shipmentId, carrier = "UPS", tracking = "1Z", principalId = null) }
    }

    // --- repack: not-found and update-null error paths ---

    @Test
    fun `repack throws when the shipment does not exist`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns null
        assertFailsWith<IllegalStateException> { service.repack(shipmentId, principalId = null) }
    }

    @Test
    fun `repack throws when the row vanished before the update`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.UNABLE_TO_PACKAGE,
            unpacked = listOf(ShipmentLine(catalogProductId = cpA, inventoryId = invA, sku = "SKU-A", quantity = 1)),
        )
        coEvery { containerService.getByCompany(companyId) } returns emptyList()
        coEvery { productService.get(any()) } returns product(5.0)
        coEvery { shipmentRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.repack(shipmentId, principalId = null) }
    }

    // --- refreshTracking: not-found, update-null, provider-null, and the update-field elvis arms ---

    @Test
    fun `refreshTracking throws when the shipment does not exist`() = runTest {
        val shipmentId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns null
        assertFailsWith<IllegalStateException> { service.refreshTracking(shipmentId, principalId = null) }
    }

    @Test
    fun `refreshTracking throws when the row vanished before the update`() = runTest {
        val shipmentId = UUID.random()
        val spId = UUID.random()
        bosca.di.provides<ShippingRateProvider>(name = "fixed-rate", singleton = true) { FixedRateShippingRateProvider() }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "fixed-rate")
        coEvery { shipmentRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.refreshTracking(shipmentId, principalId = null) }
    }

    @Test
    fun `refreshTracking is a no-op when the center has no shipping provider`() = runTest {
        // resolveTracking's `getShippingProvider ?: return null` arm: a center with no provider config
        // yields no reading, so the shipment is not advanced.
        val shipmentId = UUID.random()
        val spId = UUID.random()
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.SHIPPED,
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns null // resolveTracking returns null

        service.refreshTracking(shipmentId, principalId = null)

        coVerify(exactly = 0) { shipmentRepository.update(any()) }
    }

    @Test
    fun `refreshTracking falls back to the prior carrier status and stamps a supplied delivery time`() = runTest {
        // The reading carries no carrierStatus (so `update.carrierStatus ?: shipment.carrierStatus`
        // keeps the prior value) but does carry a delivery time (so `update.delivered ?: ...` takes it).
        val shipmentId = UUID.random()
        val spId = UUID.random()
        val deliveredAt = bosca.serialization.OffsetDateTime.now()
        bosca.di.provides<ShippingRateProvider>(name = "partial", singleton = true) {
            mockk { coEvery { track(any(), any()) } returns TrackingUpdate(status = ShipmentStatus.DELIVERED, carrierStatus = null, delivered = deliveredAt) }
        }
        coEvery { shipmentRepository.getForUpdate(shipmentId) } returns Shipment(
            id = shipmentId, cartId = cartId, storeId = storeId, companyId = companyId, fulfillmentCenterId = centerA,
            status = ShipmentStatus.OUT_FOR_DELIVERY, carrierStatus = "prior status",
        )
        coEvery { fulfillmentService.getCenter(centerA) } returns center(spId)
        coEvery { providerService.getShippingProvider(spId) } returns shippingProvider(spId, "partial")
        val updated = slot<Shipment>()
        coEvery { shipmentRepository.update(capture(updated)) } answers { firstArg() }

        service.refreshTracking(shipmentId, principalId = null)

        assertEquals(ShipmentStatus.DELIVERED, updated.captured.status)
        assertEquals("prior status", updated.captured.carrierStatus) // carrierStatus was null -> prior kept
        assertEquals(deliveredAt, updated.captured.delivered)        // delivered was present -> taken
    }
}
