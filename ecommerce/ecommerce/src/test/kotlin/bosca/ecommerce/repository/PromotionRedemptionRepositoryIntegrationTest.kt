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
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionRedemption
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres proof of the per-account promotion frequency window:
 * `getByPromotionAndAccountSince` must apply `created >= :since` so a redemption inside the window
 * counts and one before it does not. The unit suite mocks this repository, so the windowing predicate
 * — the per-account limit guard — is proven HERE against live SQL.
 */
@OptIn(ExperimentalUuidApi::class)
class PromotionRedemptionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_redemption_test")
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
    private val accountRepository = AccountRepositoryImpl()
    private val cartRepository = CartRepositoryImpl()
    private val promotionRepository = PromotionRepositoryImpl()
    private val redemptionRepository = PromotionRedemptionRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.promotions") { it.execute() }
                connection().useStatement("DELETE FROM ecom.carts") { it.execute() }
                connection().useStatement("DELETE FROM ecom.accounts") { it.execute() }
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

    /** store(chain) + promotion + account + cart, so promotion_redemptions' FKs are satisfiable. */
    private data class Fixture(val companyId: UUID, val storeId: UUID, val accountId: UUID, val cartId: UUID, val promotionId: UUID)

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
                val accountId = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER)).id
                val cartId = cartRepository.add(Cart(companyId = companyId, storeId = storeId, accountId = accountId, expires = OffsetDateTime.now().plusSeconds(3600))).id
                val promotionId = promotionRepository.add(
                    Promotion(
                        storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("5.00")),
                        starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
                    ),
                ).id
                f = Fixture(companyId, storeId, accountId, cartId, promotionId)
            }
        }
        return f
    }

    @Test
    fun `add then getByCart returns the cart's redemptions`() {
        val fx = fixture()
        withDb {
            transaction {
                redemptionRepository.add(PromotionRedemption(promotionId = fx.promotionId, accountId = fx.accountId, cartId = fx.cartId))
            }
        }

        var byCart: List<PromotionRedemption> = emptyList()
        withDb { transaction { byCart = redemptionRepository.getByCart(fx.cartId) } }
        assertEquals(1, byCart.size)
        assertEquals(fx.promotionId, byCart.first().promotionId)
        assertEquals(fx.cartId, byCart.first().cartId)
    }

    @Test
    fun `deleteByCartAndPromotion removes only the matching redemption`() {
        val fx = fixture()
        // A second promotion on the same cart so we can prove the delete is scoped.
        lateinit var otherPromotionId: UUID
        withDb {
            transaction {
                otherPromotionId = promotionRepository.add(
                    Promotion(
                        storeId = fx.storeId, code = "OTHER", name = "Other", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("1.00")),
                        starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
                    ),
                ).id
                redemptionRepository.add(PromotionRedemption(promotionId = fx.promotionId, accountId = fx.accountId, cartId = fx.cartId))
                redemptionRepository.add(PromotionRedemption(promotionId = otherPromotionId, accountId = fx.accountId, cartId = fx.cartId))
            }
        }

        withDb { transaction { redemptionRepository.deleteByCartAndPromotion(fx.cartId, fx.promotionId) } }

        var byCart: List<PromotionRedemption> = emptyList()
        withDb { transaction { byCart = redemptionRepository.getByCart(fx.cartId) } }
        assertEquals(1, byCart.size, "only the targeted promotion's redemption is removed")
        assertEquals(otherPromotionId, byCart.first().promotionId)
    }

    @Test
    fun `getByPromotionAndAccountSince windows on created and excludes redemptions before the boundary`() {
        val fx = fixture()
        val since = OffsetDateTime.now().minusDays(7)

        withDb {
            transaction {
                // Inside the window: created defaults to now() in the row.
                redemptionRepository.add(PromotionRedemption(promotionId = fx.promotionId, accountId = fx.accountId, cartId = fx.cartId))
                // Before the window: backdate `created` to 30 days ago directly (created is DB-defaulted on insert).
                connection().useStatement(
                    "insert into ecom.promotion_redemptions (promotion_id, account_id, cart_id, created) values (?, ?, ?, now() - interval '30 days')",
                ) { stmt ->
                    stmt.setObject(1, fx.promotionId.toJavaUuid())
                    stmt.setObject(2, fx.accountId.toJavaUuid())
                    stmt.setObject(3, fx.cartId.toJavaUuid())
                    stmt.execute()
                }
            }
        }

        var inWindow: List<PromotionRedemption> = emptyList()
        withDb { transaction { inWindow = redemptionRepository.getByPromotionAndAccountSince(fx.promotionId, fx.accountId, since) } }

        assertEquals(1, inWindow.size, "only the redemption with created >= :since is counted toward the per-account limit")
        assertTrue(inWindow.all { !it.created.isBefore(since) })
    }
}
