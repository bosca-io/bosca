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
import bosca.ecommerce.model.SubscriptionStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Real-Postgres proof of the subscription columns + renewal sweep. Specifically: the newly
 * added `external` boolean must persist+read back, and `getDueForRenewal` must EXCLUDE externally-billed
 * subscriptions (the store bills them, never the dunning sweep). The unit suite mocks this repository,
 * so the `external = false` predicate — the guard that store-billed subs are never dunned — is proven
 * HERE against live SQL, not assumed.
 */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_subscription_test")
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

    /** company -> account, and store(chain) -> plan group -> plan, so subscriptions' FKs are satisfiable. */
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

    private fun Fixture.subscription(
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        external: Boolean = false,
        renews: OffsetDateTime = OffsetDateTime.now(),
    ) = Subscription(
        storeId = storeId,
        accountId = accountId,
        planId = planId,
        planGroupId = planGroupId,
        status = status,
        price = Money.of("9.99"),
        interval = 1,
        intervalUnit = IntervalUnit.MONTHS,
        renews = renews,
        external = external,
    )

    @Test
    fun `add and get round-trip a subscription including the new external column as true`() {
        val fx = fixture()
        lateinit var created: Subscription
        withDb { transaction { created = subscriptionRepository.add(fx.subscription(external = true)) } }

        // Prove the column persisted true straight out of `add`'s returning *.
        assertTrue(created.external, "the inserted row should report external = true")

        var loaded: Subscription? = null
        withDb { transaction { loaded = subscriptionRepository.get(created.id) } }
        val sub = requireNotNull(loaded)
        assertTrue(sub.external, "external = true must read back from the row (the renewal sweep depends on it)")
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(Money.of("9.99"), sub.price)
    }

    @Test
    fun `getDueForRenewal includes the non-external past-due subscription and excludes the external one`() {
        val fx = fixture()
        val past = OffsetDateTime.now().minusSeconds(3600)
        lateinit var internal: Subscription
        withDb {
            transaction {
                // ACTIVE, store-renewable, renews in the past -> due.
                internal = subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.ACTIVE, external = false, renews = past))
                // ACTIVE, externally billed, renews in the past -> must NEVER be selected (guard).
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.ACTIVE, external = true, renews = past))
                // CANCELLED, renews in the past -> excluded by the status predicate.
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.CANCELLED, external = false, renews = past))
                // ACTIVE but renews in the future -> not yet due.
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.ACTIVE, external = false, renews = OffsetDateTime.now().plusSeconds(3600)))
            }
        }

        var due: List<Subscription> = emptyList()
        withDb { transaction { due = subscriptionRepository.getDueForRenewal(10) } }

        assertEquals(1, due.size, "exactly the one non-external, active, past-due subscription is due")
        assertEquals(internal.id, due.first().id)
        assertTrue(due.none { it.external }, "an externally-billed subscription must never be dunned")
    }

    @Test
    fun `getByAccountAndGroup returns all and getLiveByAccountAndGroup excludes terminal subscriptions`() {
        val fx = fixture()
        withDb {
            transaction {
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.ACTIVE))
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.CANCELLED))
                subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.EXPIRED))
            }
        }

        var all: List<Subscription> = emptyList()
        var live: List<Subscription> = emptyList()
        withDb {
            transaction {
                all = subscriptionRepository.getByAccountAndGroup(fx.accountId, fx.planGroupId)
                live = subscriptionRepository.getLiveByAccountAndGroup(fx.accountId, fx.planGroupId)
            }
        }

        assertEquals(3, all.size, "getByAccountAndGroup returns every non-deleted subscription")
        assertEquals(1, live.size, "getLiveByAccountAndGroup drops cancelled/expired")
        assertEquals(SubscriptionStatus.ACTIVE, live.first().status)
    }

    @Test
    fun `update mutates lifecycle fields and softDelete makes get return null`() {
        val fx = fixture()
        lateinit var created: Subscription
        withDb { transaction { created = subscriptionRepository.add(fx.subscription(status = SubscriptionStatus.ACTIVE)) } }

        var updated: Subscription? = null
        withDb {
            transaction {
                updated = subscriptionRepository.update(
                    created.copy(status = SubscriptionStatus.PAST_DUE, paymentFailures = 2, renewals = 1),
                )
            }
        }
        val u = requireNotNull(updated)
        assertEquals(SubscriptionStatus.PAST_DUE, u.status)
        assertEquals(2, u.paymentFailures)
        assertEquals(1, u.renewals)

        withDb { transaction { subscriptionRepository.softDelete(created.id) } }
        var afterDelete: Subscription? = created
        withDb { transaction { afterDelete = subscriptionRepository.get(created.id) } }
        assertNull(afterDelete, "a soft-deleted subscription must not be returned by get")
    }
}
