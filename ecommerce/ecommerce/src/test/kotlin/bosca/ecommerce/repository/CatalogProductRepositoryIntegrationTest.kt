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
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.ecommerce.model.QuantityRequirements
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for catalog entries: the `product_type` enum cast, `extras::jsonb` +
 * `promotions varchar[]` round-trips, the price-change update, and — the acceptance focus —
 * active-window (`starts <= now < ends`) and ProductType filtering matching legacy semantics.
 */
@OptIn(ExperimentalUuidApi::class)
class CatalogProductRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_cp_test")
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
                connection().useStatement("DELETE FROM ecom.catalog_products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
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

    /** Returns a (catalogId, productId of a freshly created product of [type]) for the same company. */
    private fun fixture(): Triple<UUID, UUID, UUID> {
        lateinit var catalogId: UUID
        lateinit var companyId: UUID
        lateinit var manufacturerId: UUID
        withDb {
            transaction {
                companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
            }
        }
        return Triple(companyId, manufacturerId, catalogId)
    }

    private fun newProduct(companyId: UUID, manufacturerId: UUID, sku: String, type: ProductType): UUID {
        lateinit var id: UUID
        withDb {
            transaction {
                id = productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = sku,
                        metadataId = UUID.random(), metadataVersion = 1, type = type,
                    ),
                ).id
            }
        }
        return id
    }

    @Test
    fun `entry round-trips price, jsonb extras, and a promotions array`() {
        val (companyId, manufacturerId, catalogId) = fixture()
        val productId = newProduct(companyId, manufacturerId, "SKU-1", ProductType.PHYSICAL)
        val extras = QuantityRequirements(min = 1)
        lateinit var created: CatalogProduct
        withDb {
            transaction {
                created = catalogProductRepository.add(
                    CatalogProduct(
                        catalogId = catalogId, productId = productId, type = ProductType.PHYSICAL,
                        price = Money.of("12.50"), promotions = listOf("SUMMER", "VIP"), extras = extras,
                    ),
                )
            }
        }
        var fetched: CatalogProduct? = null
        withDb { transaction { fetched = catalogProductRepository.get(created.id) } }
        assertEquals(Money.of("12.50"), fetched?.price)
        assertEquals(listOf("SUMMER", "VIP"), fetched?.promotions)
        assertEquals(extras, fetched?.extras)
        assertEquals(ProductType.PHYSICAL, fetched?.type)
    }

    @Test
    fun `getByCatalog filters by active window and product type`() {
        val (companyId, manufacturerId, catalogId) = fixture()
        val physical = newProduct(companyId, manufacturerId, "PHYS", ProductType.PHYSICAL)
        val expiredProduct = newProduct(companyId, manufacturerId, "EXP", ProductType.PHYSICAL)
        val subscription = newProduct(companyId, manufacturerId, "SUB", ProductType.SUBSCRIPTION)

        val now = OffsetDateTime.now()
        withDb {
            transaction {
                catalogProductRepository.add(
                    CatalogProduct(
                        catalogId = catalogId, productId = physical, type = ProductType.PHYSICAL,
                        price = Money.of("1.00"), starts = now.minusDays(1), ends = now.plusDays(1),
                    ),
                )
                catalogProductRepository.add(
                    CatalogProduct(
                        catalogId = catalogId, productId = expiredProduct, type = ProductType.PHYSICAL,
                        price = Money.of("2.00"), starts = now.minusDays(2), ends = now.minusDays(1),
                    ),
                )
                catalogProductRepository.add(
                    CatalogProduct(
                        catalogId = catalogId, productId = subscription, type = ProductType.SUBSCRIPTION,
                        price = Money.of("3.00"), starts = now.minusDays(1), ends = now.plusDays(1),
                    ),
                )
            }
        }

        var all = 0
        var active = 0
        var activePhysical = 0
        var activeSubscription = 0
        withDb {
            transaction {
                all = catalogProductRepository.getByCatalog(catalogId, null, false, 0, 25).size
                active = catalogProductRepository.getByCatalog(catalogId, null, true, 0, 25).size
                activePhysical = catalogProductRepository.getByCatalog(catalogId, ProductType.PHYSICAL, true, 0, 25).size
                activeSubscription =
                    catalogProductRepository.getByCatalog(catalogId, ProductType.SUBSCRIPTION, true, 0, 25).size
            }
        }
        assertEquals(3, all, "all entries (window ignored)")
        assertEquals(2, active, "only the two active entries")
        assertEquals(1, activePhysical, "active + PHYSICAL")
        assertEquals(1, activeSubscription, "active + SUBSCRIPTION")
    }

    @Test
    fun `update changes the price`() {
        val (companyId, manufacturerId, catalogId) = fixture()
        val productId = newProduct(companyId, manufacturerId, "SKU-2", ProductType.PHYSICAL)
        lateinit var created: CatalogProduct
        withDb {
            transaction {
                created = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.PHYSICAL, price = Money.of("10.00")),
                )
            }
        }
        var updated: CatalogProduct? = null
        withDb { transaction { updated = catalogProductRepository.update(created.copy(price = Money.of("8.00"))) } }
        assertEquals(Money.of("8.00"), updated?.price)
    }
}
