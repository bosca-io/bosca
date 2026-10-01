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
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnLine
import bosca.ecommerce.model.ReturnStatus
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
 * Real-Postgres tests for [ReturnRepositoryImpl]: the native `return_status` / `refund_tender`
 * enum-casts and the `lines` jsonb round-trip, plus the `getByStoreAndStatus` enum-bound query. Drives real
 * SQL so a wrong enum label or serializer drift is caught — a mock can't.
 */
@OptIn(ExperimentalUuidApi::class)
class ReturnRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_return_test")
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
    private val storeRepository = StoreRepositoryImpl()
    private val cartRepository = CartRepositoryImpl()
    private val returnRepository = ReturnRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.returns") { it.execute() }
                connection().useStatement("DELETE FROM ecom.carts") { it.execute() }
                connection().useStatement("DELETE FROM ecom.stores") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalog_products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
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

    private data class Fixture(val companyId: UUID, val storeId: UUID, val cartId: UUID)

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
                val storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = paymentProviderId, shippingCatalogProductId = shippingCp),
                ).id
                val cartId = cartRepository.add(Cart(companyId = companyId, storeId = storeId, expires = OffsetDateTime.now().plusSeconds(3600))).id
                f = Fixture(companyId, storeId, cartId)
            }
        }
        return f
    }

    @Test
    fun `return round-trips the status and tender enums and the lines jsonb`() {
        val fx = fixture()
        val line = ReturnLine(itemId = UUID.random(), catalogProductId = UUID.random(), quantity = 3)

        lateinit var savedId: UUID
        withDb {
            transaction {
                savedId = returnRepository.add(
                    Return(
                        cartId = fx.cartId, storeId = fx.storeId, companyId = fx.companyId,
                        status = ReturnStatus.REQUESTED, reason = "damaged", tender = RefundTender.CHECK,
                        lines = listOf(line), refundedAmount = Money.of("12.50"), checkNumber = "CHK-9",
                    ),
                ).id
            }
        }

        lateinit var loaded: Return
        withDb { transaction { loaded = returnRepository.get(savedId)!! } }

        assertEquals(ReturnStatus.REQUESTED, loaded.status)   // native return_status enum-cast round-trip
        assertEquals(RefundTender.CHECK, loaded.tender)       // native refund_tender enum-cast round-trip
        assertEquals(3, loaded.lines.single().quantity)       // lines jsonb round-trip
        assertEquals(Money.of("12.50"), loaded.refundedAmount)
        assertEquals("CHK-9", loaded.checkNumber)
    }

    @Test
    fun `update advances the status and getByStoreAndStatus binds the enum`() {
        val fx = fixture()
        lateinit var id: UUID
        withDb {
            transaction {
                id = returnRepository.add(Return(cartId = fx.cartId, storeId = fx.storeId, companyId = fx.companyId, status = ReturnStatus.REQUESTED)).id
            }
        }
        // Not yet APPROVED -> the enum-bound query excludes it.
        withDb { transaction { assertTrue(returnRepository.getByStoreAndStatus(fx.storeId, ReturnStatus.APPROVED, 0, 50).isEmpty()) } }

        withDb { transaction { returnRepository.update(returnRepository.get(id)!!.copy(status = ReturnStatus.APPROVED)) } }
        withDb {
            transaction {
                assertEquals(ReturnStatus.APPROVED, returnRepository.get(id)!!.status)
                assertEquals(1, returnRepository.getByStoreAndStatus(fx.storeId, ReturnStatus.APPROVED, 0, 50).size)
            }
        }
    }
}
