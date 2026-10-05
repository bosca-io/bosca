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
import bosca.ecommerce.model.BuyOneGetOneRule
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
import kotlin.test.assertIs
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
 * Real-Postgres tests for promotions: the sealed [bosca.ecommerce.model.Rule] round-tripping through
 * the `rule` jsonb via `JsonbMapper` (strong typing, polymorphic discriminator), and the redemption
 * concurrency guarantee — concurrent redeems of a limited promotion never exceed its quantity.
 */
@OptIn(ExperimentalUuidApi::class)
class PromotionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_promotion_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 8),
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
                connection().useStatement("DELETE FROM ecom.promotion_redemptions") { it.execute() }
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

    private fun storeId(): UUID {
        lateinit var storeId: UUID
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                val catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
                val productId = productRepository.add(Product(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING)).id
                val cp = catalogProductRepository.add(CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00"))).id
                val pp = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                storeId = storeRepository.add(Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = pp, shippingCatalogProductId = cp)).id
            }
        }
        return storeId
    }

    @Test
    fun `sealed rule round-trips through the jsonb column`() {
        val storeId = storeId()
        lateinit var amountId: UUID
        lateinit var bogoId: UUID
        withDb {
            transaction {
                amountId = promotionRepository.add(promotion(storeId, "AMT", AmountOffCartRule(Money.of("5.00")))).id
                bogoId = promotionRepository.add(promotion(storeId, "BOGO", BuyOneGetOneRule(buyQuantity = 2, getQuantity = 1))).id
            }
        }

        var amount: Promotion? = null
        var bogo: Promotion? = null
        withDb { transaction { amount = promotionRepository.get(amountId); bogo = promotionRepository.get(bogoId) } }

        val amountRule = assertIs<AmountOffCartRule>(amount?.rule)
        assertEquals(Money.of("5.00"), amountRule.amount)
        val bogoRule = assertIs<BuyOneGetOneRule>(bogo?.rule)
        assertEquals(2, bogoRule.buyQuantity)
        assertEquals(1, bogoRule.getQuantity)
    }

    @Test
    fun `concurrent redeem of a limited promotion never exceeds quantity`() {
        val storeId = storeId()
        lateinit var promotionId: UUID
        withDb {
            transaction {
                promotionId = promotionRepository.add(promotion(storeId, "LIMITED", AmountOffCartRule(Money.of("1.00")))).id
                availabilityRepository.add(PromotionAvailability(promotionId = promotionId, quantity = 3))
            }
        }

        val outcomes = runBlocking {
            (1..8).map { async(Dispatchers.IO) { runCatching { redeemOnce(promotionId) } } }.awaitAll()
        }

        assertEquals(3, outcomes.count { it.getOrNull() == true }, "exactly quantity redemptions should succeed")
        var redeemed = -1L
        withDb { transaction { redeemed = availabilityRepository.get(promotionId)!!.redeemed } }
        assertEquals(3L, redeemed)
    }

    /** Redeem on a dedicated connection so concurrent calls contend the availability row lock. */
    private suspend fun redeemOnce(promotionId: UUID): Boolean {
        val manager = pool.connection()
        return try {
            withContext(manager.asCoroutineContext()) { transaction { availabilityRepository.redeem(promotionId) != null } }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    private fun promotion(storeId: UUID, code: String, rule: bosca.ecommerce.model.Rule) = Promotion(
        storeId = storeId, code = code, name = code, type = PromotionType.CART, rule = rule,
        starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
    )
}
