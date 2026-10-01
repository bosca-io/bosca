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
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShippingProvider
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * Real-Postgres proof of the inventory-sync queries the unit tests only mock: `getSyncable`
 * (live, non-"manual" centers only), `touchSynced` (stamps `last_synced`), and `getByCenter`. A SQL
 * typo in any of these would fail only at runtime in the scheduled sweep — exercised here instead.
 */
@OptIn(ExperimentalUuidApi::class)
class FulfillmentSyncRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_fulfillment_sync_test")
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
    private val productRepository = ProductRepositoryImpl()
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()
    private val fulfillmentCenterRepository = FulfillmentCenterRepositoryImpl()
    private val inventoryRepository = InventoryRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.product_inventory") { it.execute() }
                connection().useStatement("DELETE FROM ecom.fulfillment_centers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
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

    private data class Ctx(val companyId: UUID, val shippingProviderId: UUID)

    private fun ctx(): Ctx {
        lateinit var c: Ctx
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val shippingProviderId = shippingProviderRepository.add(
                    ShippingProvider(companyId = companyId, name = "Ship", key = "ship-1", providerKey = "manual"),
                ).id
                c = Ctx(companyId, shippingProviderId)
            }
        }
        return c
    }

    private fun center(ctx: Ctx, connectorKey: String, name: String): UUID {
        lateinit var id: UUID
        withDb {
            transaction {
                id = fulfillmentCenterRepository.add(
                    FulfillmentCenter(
                        companyId = ctx.companyId, name = name, connectorKey = connectorKey, shippingProviderId = ctx.shippingProviderId,
                        address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000",
                    ),
                ).id
            }
        }
        return id
    }

    @Test
    fun `getSyncable returns only live centers with a non-manual connector`() {
        val c = ctx()
        val wms = center(c, connectorKey = "wms", name = "WMS")
        center(c, connectorKey = "manual", name = "Manual") // excluded — no external sync
        val deleted = center(c, connectorKey = "wms", name = "Retired")
        withDb { transaction { connection().useStatement("UPDATE ecom.fulfillment_centers SET deleted = now() WHERE id = '$deleted'") { it.execute() } } }

        lateinit var syncable: List<FulfillmentCenter>
        withDb { transaction { syncable = fulfillmentCenterRepository.getSyncable() } }

        assertEquals(listOf(wms), syncable.map { it.id }, "only the live wms center is syncable")
    }

    @Test
    fun `touchSynced stamps last_synced`() {
        val c = ctx()
        val wms = center(c, connectorKey = "wms", name = "WMS")

        withDb { transaction { assertNull(fulfillmentCenterRepository.get(wms)?.lastSynced, "starts un-synced") } }

        lateinit var rows: Number
        withDb { transaction { rows = fulfillmentCenterRepository.touchSynced(wms) } }
        assertEquals(1, rows.toInt(), "one center stamped")

        withDb { transaction { assertNotNull(fulfillmentCenterRepository.get(wms)?.lastSynced, "last_synced is set after touchSynced") } }
    }

    @Test
    fun `getByCenter returns the inventory rows at a center`() {
        val c = ctx()
        val centerA = center(c, connectorKey = "wms", name = "A")
        val centerB = center(c, connectorKey = "wms", name = "B")
        withDb {
            transaction {
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = c.companyId, name = "Acme")).id
                val productId = productRepository.add(
                    Product(companyId = c.companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL),
                ).id
                inventoryRepository.add(Inventory(productId = productId, fulfillmentCenterId = centerA, sku = "A-1", quantity = 5))
                inventoryRepository.add(Inventory(productId = productId, fulfillmentCenterId = centerA, sku = "A-2", quantity = 3))
                inventoryRepository.add(Inventory(productId = productId, fulfillmentCenterId = centerB, sku = "B-1", quantity = 9))
            }
        }

        lateinit var atA: List<Inventory>
        withDb { transaction { atA = inventoryRepository.getByCenter(centerA) } }

        assertEquals(setOf("A-1", "A-2"), atA.map { it.sku }.toSet(), "only center A's rows are returned")
        assertTrue(atA.all { it.fulfillmentCenterId == centerA })
    }
}
