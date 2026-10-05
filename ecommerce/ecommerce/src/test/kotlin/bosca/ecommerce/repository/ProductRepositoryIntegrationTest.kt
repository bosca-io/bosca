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
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
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
import bosca.ecommerce.model.SubscriptionProductConfiguration
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the product repository: the `(:type)::ecom.product_type` enum cast, the
 * `configuration::jsonb` round-trip, the `(manufacturer_id, manufacturer_sku)` + `metadata_id`
 * unique constraints, and the pin-advance update.
 */
@OptIn(ExperimentalUuidApi::class)
class ProductRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_product_test")
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

    private fun fixture(): Pair<UUID, UUID> {
        lateinit var companyId: UUID
        lateinit var manufacturerId: UUID
        withDb {
            transaction {
                companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
            }
        }
        return companyId to manufacturerId
    }

    @Test
    fun `product CRUD round-trips with enum cast, jsonb config, pin advance`() {
        val (companyId, manufacturerId) = fixture()
        val metadataId = UUID.random()
        val configuration = SubscriptionProductConfiguration(planGroupId = UUID.random())
        lateinit var created: Product
        withDb {
            transaction {
                created = productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU-1",
                        metadataId = metadataId, metadataVersion = 1, type = ProductType.SUBSCRIPTION,
                        configuration = configuration,
                    ),
                )
            }
        }
        assertEquals(ProductType.SUBSCRIPTION, created.type)
        assertEquals(configuration, created.configuration)

        var byId: Product? = null
        var byMetadata: Product? = null
        var byCompany: List<Product> = emptyList()
        withDb {
            transaction {
                byId = productRepository.get(created.id)
                byMetadata = productRepository.getByMetadataId(metadataId)
                byCompany = productRepository.getByCompany(companyId, 0, 25)
            }
        }
        assertEquals(created.id, byId?.id)
        assertEquals(created.id, byMetadata?.id)
        assertEquals(1, byCompany.size)

        var pinned: Product? = null
        withDb { transaction { pinned = productRepository.updatePin(created.id, 3) } }
        assertEquals(3, pinned?.metadataVersion)
    }

    @Test
    fun `manufacturer_sku is unique per manufacturer`() {
        val (companyId, manufacturerId) = fixture()
        withDb {
            transaction {
                productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "DUP",
                        metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
                    ),
                )
            }
        }
        assertFailsWith<Exception> {
            withDb {
                transaction {
                    productRepository.add(
                        Product(
                            companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "DUP",
                            metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `metadata_id is unique across products`() {
        val (companyId, manufacturerId) = fixture()
        val metadataId = UUID.random()
        withDb {
            transaction {
                productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "A",
                        metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
                    ),
                )
            }
        }
        assertFailsWith<Exception> {
            withDb {
                transaction {
                    productRepository.add(
                        Product(
                            companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "B",
                            metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
                        ),
                    )
                }
            }
        }
    }
}
