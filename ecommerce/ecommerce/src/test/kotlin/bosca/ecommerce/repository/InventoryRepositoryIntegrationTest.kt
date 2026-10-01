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
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.service.EcomAuditService
import bosca.ecommerce.service.InventoryServiceImpl
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the inventory reservation state machine: the full
 * reserve → commit → ship → release walk plus the oversell guarantee — two concurrent reservations
 * of the last unit serialize on the `select … for update` row lock so exactly one wins.
 */
@OptIn(ExperimentalUuidApi::class)
class InventoryRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_inventory_test")
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

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private val companyRepository = CompanyRepositoryImpl()
    private val manufacturerRepository = ManufacturerRepositoryImpl()
    private val productRepository = ProductRepositoryImpl()
    private val catalogRepository = CatalogRepositoryImpl()
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()
    private val fulfillmentCenterRepository = FulfillmentCenterRepositoryImpl()
    private val inventoryRepository = InventoryRepositoryImpl()

    /** Real repo + a relaxed audit mock — the state machine + row lock are what we exercise. */
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private val service = InventoryServiceImpl(inventoryRepository, auditService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        // ship()/releasePending() dispatch inventory events, which resolve a PubSubService.
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(EcommerceMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM ecom.product_inventory") { it.execute() }
                connection().useStatement("DELETE FROM ecom.fulfillment_centers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
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

    /** company -> manufacturer -> product, shipping_provider -> fulfillment_center -> inventory(quantity). */
    private fun inventoryWith(quantity: Int): UUID {
        lateinit var inventoryId: UUID
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default"))
                val productId = productRepository.add(
                    Product(
                        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU",
                        metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
                    ),
                ).id
                val shippingProviderId = shippingProviderRepository.add(
                    ShippingProvider(companyId = companyId, name = "Ship", key = "ship-1", providerKey = "manual"),
                ).id
                val centerId = fulfillmentCenterRepository.add(
                    FulfillmentCenter(
                        companyId = companyId, name = "Main", connectorKey = "manual", shippingProviderId = shippingProviderId,
                        address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000",
                    ),
                ).id
                inventoryId = inventoryRepository.add(
                    Inventory(productId = productId, fulfillmentCenterId = centerId, sku = "SKU", quantity = quantity),
                ).id
            }
        }
        return inventoryId
    }

    private fun read(id: UUID): Inventory {
        lateinit var inv: Inventory
        withDb { transaction { inv = inventoryRepository.get(id)!! } }
        return inv
    }

    @Test
    fun `full reserve to commit to ship to release walk`() {
        val id = inventoryWith(quantity = 5)

        withDb { service.reserve(id, 2) }
        read(id).let { assertEquals(2, it.inCart); assertEquals(3, it.available) }

        withDb { service.commitToPending(id, 2) }
        read(id).let { assertEquals(0, it.inCart); assertEquals(2, it.pending); assertEquals(3, it.available) }

        withDb { service.ship(id, 1) }
        read(id).let { assertEquals(4, it.quantity); assertEquals(1, it.pending); assertEquals(3, it.available) }

        withDb { service.releasePending(id, 1) }
        read(id).let { assertEquals(0, it.pending); assertEquals(4, it.available) }
    }

    @Test
    fun `concurrent reservations of the last unit - exactly one wins`() {
        val id = inventoryWith(quantity = 1)

        val outcomes = runBlocking {
            (1..2).map {
                async(Dispatchers.IO) { runCatching { reserveOnce(id) } }
            }.awaitAll()
        }

        assertEquals(1, outcomes.count { it.isSuccess }, "exactly one reservation should succeed")
        assertEquals(1, read(id).inCart)
        assertEquals(0, read(id).available)
    }

    /** Reserve on a dedicated connection so two of these truly run concurrently and contend the lock. */
    private suspend fun reserveOnce(id: UUID) {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { service.reserve(id, 1) }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    @Test
    fun `reserve fails when nothing is available`() {
        val id = inventoryWith(quantity = 1)
        withDb { service.reserve(id, 1) }
        assertFailsWith<IllegalStateException> { withDb { service.reserve(id, 1) } }
    }
}
