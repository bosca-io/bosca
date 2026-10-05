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
import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionAvailability
import bosca.ecommerce.model.PromotionType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for `ecom.promotion_availability` — the redemption-limit counter. The
 * concurrency guarantee is proven in [PromotionRepositoryIntegrationTest]; this class covers the
 * single-row semantics against the live `check (redeemed >= 0 and redeemed <= quantity)` constraint:
 * add/get, the guarded `redeem` returning null when sold out, and `release` clamping at zero.
 */
@OptIn(ExperimentalUuidApi::class)
class PromotionAvailabilityRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_promotion_availability_test")
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
    private val catalogRepository = CatalogRepositoryImpl()
    private val catalogProductRepository = CatalogProductRepositoryImpl()
    private val paymentProviderRepository = PaymentProviderRepositoryImpl()
    private val storeRepository = StoreRepositoryImpl()
    private val promotionRepository = PromotionRepositoryImpl()
    private val availabilityRepository = PromotionAvailabilityRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.promotion_availability") { it.execute() }
                connection().useStatement("DELETE FROM ecom.promotions") { it.execute() }
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

    private fun promotionId(): UUID {
        lateinit var promotionId: UUID
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                val catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
                val productId = productRepository.add(
                    Product(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING),
                ).id
                val cp = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00")),
                ).id
                val pp = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                val storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = pp, shippingCatalogProductId = cp),
                ).id
                promotionId = promotionRepository.add(
                    Promotion(
                        storeId = storeId, code = "LIMITED", name = "Limited", type = PromotionType.CART,
                        rule = AmountOffCartRule(Money.of("1.00")),
                        starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
                    ),
                ).id
            }
        }
        return promotionId
    }

    @Test
    fun `add then get returns the counter and get on an unbounded promotion is null`() {
        val promotionId = promotionId()
        withDb {
            transaction {
                val added = availabilityRepository.add(PromotionAvailability(promotionId = promotionId, quantity = 5))
                assertEquals(5L, added.quantity)
                assertEquals(0L, added.redeemed)

                val loaded = assertNotNull(availabilityRepository.get(promotionId))
                assertEquals(promotionId, loaded.promotionId)
                assertEquals(5L, loaded.quantity)

                assertNull(availabilityRepository.get(UUID.random()), "no counter row -> null")
            }
        }
    }

    @Test
    fun `redeem bumps redeemed up to quantity then returns null when sold out`() {
        val promotionId = promotionId()
        withDb { transaction { availabilityRepository.add(PromotionAvailability(promotionId = promotionId, quantity = 2)) } }

        withDb {
            transaction {
                assertEquals(1L, assertNotNull(availabilityRepository.redeem(promotionId)).redeemed)
                assertEquals(2L, assertNotNull(availabilityRepository.redeem(promotionId)).redeemed)
                assertNull(availabilityRepository.redeem(promotionId), "the third redeem is over quantity -> null, no row touched")
                assertEquals(2L, availabilityRepository.get(promotionId)?.redeemed, "redeemed stays at quantity")
            }
        }
    }

    @Test
    fun `release decrements and clamps at zero`() {
        val promotionId = promotionId()
        withDb { transaction { availabilityRepository.add(PromotionAvailability(promotionId = promotionId, quantity = 3)) } }

        withDb {
            transaction {
                availabilityRepository.redeem(promotionId)
                availabilityRepository.redeem(promotionId) // redeemed = 2
                assertEquals(1L, assertNotNull(availabilityRepository.release(promotionId)).redeemed)
                assertEquals(0L, assertNotNull(availabilityRepository.release(promotionId)).redeemed)
                // Already at zero — greatest(redeemed - 1, 0) clamps, never violates the >= 0 check.
                assertEquals(0L, assertNotNull(availabilityRepository.release(promotionId)).redeemed)
            }
        }
    }
}
