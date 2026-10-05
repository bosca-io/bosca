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
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for stores + provider config rows: the `store_type` enum cast, the
 * `cart_expiration_seconds` int column, unique `identifier`, the FK chain
 * (store -> payment_provider + catalog + shipping catalog_product), and the provider
 * `configuration::jsonb` round-trip.
 */
@OptIn(ExperimentalUuidApi::class)
class StoreRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_store_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val companyRepository = CompanyRepositoryImpl()
    private val manufacturerRepository = ManufacturerRepositoryImpl()
    private val productRepository = ProductRepositoryImpl()
    private val catalogRepository = CatalogRepositoryImpl()
    private val catalogProductRepository = CatalogProductRepositoryImpl()
    private val paymentProviderRepository = PaymentProviderRepositoryImpl()
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()
    private val storeRepository = StoreRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.stores") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalog_products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.payment_providers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.shipping_providers") { it.execute() }
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

    /** Provision (companyId, catalogId, paymentProviderId, shippingCatalogProductId). */
    private data class Fixture(val companyId: UUID, val catalogId: UUID, val paymentProviderId: UUID, val shippingCp: UUID)

    private fun fixture(): Fixture {
        lateinit var f: Fixture
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                val catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
                val productId = productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP",
                        metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING,
                    ),
                ).id
                val shippingCp = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00")),
                ).id
                val paymentProviderId = paymentProviderRepository.add(
                    PaymentProvider(companyId = companyId, name = "Test", providerKey = "test"),
                ).id
                f = Fixture(companyId, catalogId, paymentProviderId, shippingCp)
            }
        }
        return f
    }

    @Test
    fun `store CRUD round-trips through the FK chain with enum + int-seconds`() {
        val fx = fixture()
        lateinit var created: Store
        withDb {
            transaction {
                created = storeRepository.add(
                    Store(
                        identifier = "main", name = "Main Store", companyId = fx.companyId, catalogId = fx.catalogId,
                        type = StoreType.VIRTUAL, paymentProviderId = fx.paymentProviderId,
                        shippingCatalogProductId = fx.shippingCp, cartExpirationSeconds = 3600,
                    ),
                )
            }
        }
        assertEquals(StoreType.VIRTUAL, created.type)
        assertEquals(3600, created.cartExpirationSeconds)

        var byId: Store? = null
        var byIdentifier: Store? = null
        withDb {
            transaction {
                byId = storeRepository.get(created.id)
                byIdentifier = storeRepository.getByIdentifier("main")
            }
        }
        assertEquals(created.id, byId?.id)
        assertEquals(created.id, byIdentifier?.id)

        var edited: Store? = null
        withDb { transaction { edited = storeRepository.update(created.copy(name = "Renamed", cartExpirationSeconds = 7200)) } }
        assertEquals("Renamed", edited?.name)
        assertEquals(7200, edited?.cartExpirationSeconds)
    }

    @Test
    fun `store identifier is unique`() {
        val fx = fixture()
        withDb {
            transaction {
                storeRepository.add(
                    Store(
                        identifier = "dup", name = "A", companyId = fx.companyId, catalogId = fx.catalogId,
                        type = StoreType.VIRTUAL, paymentProviderId = fx.paymentProviderId, shippingCatalogProductId = fx.shippingCp,
                    ),
                )
            }
        }
        assertFailsWith<Exception> {
            withDb {
                transaction {
                    storeRepository.add(
                        Store(
                            identifier = "dup", name = "B", companyId = fx.companyId, catalogId = fx.catalogId,
                            type = StoreType.PHYSICAL, paymentProviderId = fx.paymentProviderId, shippingCatalogProductId = fx.shippingCp,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `provider config rows round-trip their jsonb configuration`() {
        val fx = fixture()
        val config = KeyValueProviderConfiguration(mapOf("apiKey" to "sk_test_123"))
        var payment: PaymentProvider? = null
        var shipping: ShippingProvider? = null
        withDb {
            transaction {
                payment = paymentProviderRepository.add(
                    PaymentProvider(companyId = fx.companyId, name = "Stripe", providerKey = "stripe", configuration = config),
                ).let { paymentProviderRepository.get(it.id) }
                shipping = shippingProviderRepository.add(
                    ShippingProvider(companyId = fx.companyId, name = "Shippo", key = "shippo-1", providerKey = "shippo", configuration = config),
                ).let { shippingProviderRepository.get(it.id) }
            }
        }
        assertEquals(config, payment?.configuration)
        assertEquals(config, shipping?.configuration)
        assertEquals("shippo-1", shipping?.key)
    }
}
