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
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
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
 * Real-Postgres proof of the replay guard: `insertIfAbsent` atomically claims a (platform,
 * transaction id) via `on conflict (platform, transaction_id) do nothing returning *`, so a replayed
 * receipt returns null instead of a second claim. The unit tests only mock this repository, so the
 * actual dedupe — the security control — is proven HERE against the live unique index, not assumed.
 */
@OptIn(ExperimentalUuidApi::class)
class IapTransactionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_iap_test")
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
    private val planGroupRepository = SubscriptionPlanGroupRepositoryImpl()
    private val planRepository = SubscriptionPlanRepositoryImpl()
    private val subscriptionRepository = SubscriptionRepositoryImpl()
    private val iapTransactionRepository = IapTransactionRepositoryImpl()

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
                // Children first to respect the FK graph.
                connection().useStatement("DELETE FROM ecom.iap_transactions") { it.execute() }
                connection().useStatement("DELETE FROM ecom.subscriptions") { it.execute() }
                connection().useStatement("DELETE FROM ecom.subscription_plans") { it.execute() }
                connection().useStatement("DELETE FROM ecom.subscription_plan_groups") { it.execute() }
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

    /** company -> account, and store(chain) -> plan group -> plan, so iap_transactions' FKs are satisfiable. */
    private data class Fixture(val storeId: UUID, val accountId: UUID, val planGroupId: UUID, val planId: UUID)

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
                val planGroupId = planGroupRepository.add(SubscriptionPlanGroup(storeId = storeId, key = "grp", name = "Grp")).id
                val planId = planRepository.add(
                    SubscriptionPlan(planGroupId = planGroupId, storeId = storeId, key = "plan", name = "Plan", price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS),
                ).id
                f = Fixture(storeId, accountId, planGroupId, planId)
            }
        }
        return f
    }

    @Test
    fun `insertIfAbsent claims a transaction once and a replayed platform plus transaction id returns null`() {
        val fx = fixture()
        withDb {
            transaction {
                val first = iapTransactionRepository.insertIfAbsent(IapPlatform.IOS, "tx-1", fx.accountId, fx.planId, "prod.x")
                assertNotNull(first, "first claim should insert a row")
                assertEquals("tx-1", first.transactionId)

                // Replay: same (platform, transaction id) -> on conflict do nothing -> null, no second row.
                val replay = iapTransactionRepository.insertIfAbsent(IapPlatform.IOS, "tx-1", fx.accountId, fx.planId, "prod.x")
                assertNull(replay, "a replayed (platform, transaction id) must not claim again")

                val recorded = iapTransactionRepository.getByPlatformAndTransactionId(IapPlatform.IOS, "tx-1")
                assertEquals(first.id, recorded?.id, "exactly the original claim persists")
            }
        }
    }

    @Test
    fun `the same transaction id on a different platform is a distinct claim`() {
        val fx = fixture()
        withDb {
            transaction {
                val ios = iapTransactionRepository.insertIfAbsent(IapPlatform.IOS, "shared-tx", fx.accountId, fx.planId, null)
                val android = iapTransactionRepository.insertIfAbsent(IapPlatform.ANDROID, "shared-tx", fx.accountId, fx.planId, null)
                assertNotNull(ios, "iOS claim should succeed")
                assertNotNull(android, "Android claim with the same transaction id is a different (platform, tx) key")
                assertEquals(ios.id == android.id, false)
            }
        }
    }

    @Test
    fun `setSubscription binds the granted entitlement onto a claim`() {
        val fx = fixture()
        withDb {
            transaction {
                val claim = iapTransactionRepository.insertIfAbsent(IapPlatform.IOS, "tx-2", fx.accountId, fx.planId, null)
                assertNotNull(claim)
                val subscription = subscriptionRepository.add(
                    Subscription(
                        storeId = fx.storeId, accountId = fx.accountId, planId = fx.planId, planGroupId = fx.planGroupId,
                        price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS, external = true,
                    ),
                )

                iapTransactionRepository.setSubscription(claim.id, subscription.id)

                val bound = iapTransactionRepository.getByPlatformAndTransactionId(IapPlatform.IOS, "tx-2")
                assertEquals(subscription.id, bound?.subscriptionId, "the claim should carry the granted subscription id")
            }
        }
    }
}
