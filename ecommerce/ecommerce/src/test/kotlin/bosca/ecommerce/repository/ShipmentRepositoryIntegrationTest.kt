@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.migration.EcommerceMigration
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLine
import bosca.ecommerce.model.ShipmentParcel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for [ShipmentRepositoryImpl]: the native `shipment_status` enum-cast and
 * the `parcels`/`unpacked` jsonb columns round-trip, and the `getUnpackableByCompany` partial-index query
 * filters on the enum. Mocks can't catch a wrong enum label or a serializer drift — this drives real SQL.
 */
@OptIn(ExperimentalUuidApi::class)
class ShipmentRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_shipment_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }

    private val companyRepository = CompanyRepositoryImpl()
    private val manufacturerRepository = ManufacturerRepositoryImpl()
    private val catalogRepository = CatalogRepositoryImpl()
    private val productRepository = ProductRepositoryImpl()
    private val catalogProductRepository = CatalogProductRepositoryImpl()
    private val paymentProviderRepository = PaymentProviderRepositoryImpl()
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()
    private val fulfillmentCenterRepository = FulfillmentCenterRepositoryImpl()
    private val storeRepository = StoreRepositoryImpl()
    private val cartRepository = CartRepositoryImpl()
    private val shipmentRepository = ShipmentRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(EcommerceMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM ecom.shipments") { it.execute() }
                connection().useStatement("DELETE FROM ecom.carts") { it.execute() }
                connection().useStatement("DELETE FROM ecom.stores") { it.execute() }
                connection().useStatement("DELETE FROM ecom.fulfillment_centers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalog_products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.shipping_providers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.payment_providers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.companies") { it.execute() }
            }
        }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    private data class Fixture(val companyId: UUID, val storeId: UUID, val cartId: UUID, val fulfillmentCenterId: UUID)

    /** company -> manufacturer -> catalog -> product -> catalogProduct -> provider -> center -> store -> cart. */
    private fun fixture(): Fixture {
        lateinit var f: Fixture
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                val catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
                val productId = productRepository.add(
                    Product(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING),
                ).id
                val shippingCp = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00")),
                ).id
                val paymentProviderId = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                val shippingProviderId = shippingProviderRepository.add(
                    ShippingProvider(companyId = companyId, name = "Ship", key = "ship-1", providerKey = "manual"),
                ).id
                val centerId = fulfillmentCenterRepository.add(
                    FulfillmentCenter(
                        companyId = companyId, name = "Main", connectorKey = "manual", shippingProviderId = shippingProviderId,
                        address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000",
                    ),
                ).id
                val storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = paymentProviderId, shippingCatalogProductId = shippingCp),
                ).id
                val cartId = cartRepository.add(Cart(companyId = companyId, storeId = storeId, expires = OffsetDateTime.now().plusSeconds(3600))).id
                f = Fixture(companyId, storeId, cartId, centerId)
            }
        }
        return f
    }

    @Test
    fun `shipment round-trips the status enum and the parcels and unpacked jsonb`() {
        val fx = fixture()
        val parcel = ShipmentParcel(
            containerId = UUID.random(),
            lines = listOf(ShipmentLine(catalogProductId = UUID.random(), inventoryId = UUID.random(), sku = "SKU-1", quantity = 2)),
        )
        val unpackedLine = ShipmentLine(catalogProductId = UUID.random(), inventoryId = UUID.random(), sku = "SKU-2", quantity = 1)

        lateinit var savedId: UUID
        withDb {
            transaction {
                savedId = shipmentRepository.add(
                    Shipment(
                        cartId = fx.cartId, storeId = fx.storeId, companyId = fx.companyId, fulfillmentCenterId = fx.fulfillmentCenterId,
                        status = ShipmentStatus.SHIPPED, parcels = listOf(parcel), unpacked = listOf(unpackedLine), carrier = "UPS", tracking = "1Z-TEST",
                    ),
                ).id
            }
        }

        lateinit var loaded: Shipment
        withDb { transaction { loaded = shipmentRepository.get(savedId)!! } }

        assertEquals(ShipmentStatus.SHIPPED, loaded.status) // native enum-cast round-trip
        assertEquals(1, loaded.parcels.size)
        assertEquals(2, loaded.parcels.single().lines.single().quantity) // parcels jsonb round-trip
        assertEquals("SKU-2", loaded.unpacked.single().sku) // unpacked jsonb round-trip
        assertEquals("UPS", loaded.carrier)
        assertEquals("1Z-TEST", loaded.tracking)
    }

    @Test
    fun `update advances the status and getUnpackableByCompany filters on the enum`() {
        val fx = fixture()
        lateinit var id: UUID
        withDb {
            transaction {
                id = shipmentRepository.add(
                    Shipment(cartId = fx.cartId, storeId = fx.storeId, companyId = fx.companyId, fulfillmentCenterId = fx.fulfillmentCenterId, status = ShipmentStatus.UNABLE_TO_PACKAGE),
                ).id
            }
        }
        // Partial-index query path: the unpackable shipment is returned for its company.
        withDb { transaction { assertEquals(1, shipmentRepository.getUnpackableByCompany(fx.companyId).size) } }

        // Advance it past UNABLE_TO_PACKAGE -> it drops out of the unpackable set.
        withDb { transaction { shipmentRepository.update(shipmentRepository.get(id)!!.copy(status = ShipmentStatus.SHIPPED)) } }
        withDb {
            transaction {
                assertEquals(ShipmentStatus.SHIPPED, shipmentRepository.get(id)!!.status)
                assertTrue(shipmentRepository.getUnpackableByCompany(fx.companyId).isEmpty())
            }
        }
    }
}
