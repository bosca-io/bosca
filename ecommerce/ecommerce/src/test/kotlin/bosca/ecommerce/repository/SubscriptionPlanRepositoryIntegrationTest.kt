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
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PlanStatus
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.StandardPlanConfiguration
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * Real-Postgres tests for `ecom.subscription_plan_groups` and `ecom.subscription_plans` (only ever
 * mocked in the unit suite). The point is the native enum casts on the plan — `status`
 * (`(:status)::ecom.subscription_plan_status`) and `interval_unit`
 * (`(:intervalUnit)::ecom.subscription_interval_unit`) — round-tripping, the sealed [PlanConfiguration]
 * jsonb, and the group/store/key lookups against the live DDL.
 */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionPlanRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_subscription_plan_test")
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
    private val planGroupRepository = SubscriptionPlanGroupRepositoryImpl()
    private val planRepository = SubscriptionPlanRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.subscription_plans") { it.execute() }
                connection().useStatement("DELETE FROM ecom.subscription_plan_groups") { it.execute() }
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
                val productId = productRepository.add(
                    Product(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING),
                ).id
                val cp = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00")),
                ).id
                val pp = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = pp, shippingCatalogProductId = cp),
                ).id
            }
        }
        return storeId
    }

    @Test
    fun `plan group add then get and getByStore find it`() {
        val storeId = storeId()
        lateinit var group: SubscriptionPlanGroup
        withDb {
            transaction {
                group = planGroupRepository.add(SubscriptionPlanGroup(storeId = storeId, key = "pro", name = "Pro", description = "Pro tier", paymentRetries = 5))
            }
        }
        assertEquals(5, group.paymentRetries)

        withDb {
            transaction {
                val loaded = assertNotNull(planGroupRepository.get(group.id))
                assertEquals("pro", loaded.key)
                assertEquals("Pro tier", loaded.description)
                assertEquals(5, loaded.paymentRetries)

                val byStore = planGroupRepository.getByStore(storeId)
                assertEquals(listOf(group.id), byStore.map { it.id })
            }
        }
    }

    @Test
    fun `plan add round-trips status and interval_unit enums, the configuration jsonb, and expires`() {
        val storeId = storeId()
        lateinit var groupId: UUID
        lateinit var plan: SubscriptionPlan
        val expires = bosca.serialization.OffsetDateTime.now().plusSeconds(86_400)
        withDb {
            transaction {
                groupId = planGroupRepository.add(SubscriptionPlanGroup(storeId = storeId, key = "grp", name = "Grp")).id
                plan = planRepository.add(
                    SubscriptionPlan(
                        planGroupId = groupId, storeId = storeId, key = "monthly", name = "Monthly",
                        status = PlanStatus.INACTIVE, price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
                        configuration = StandardPlanConfiguration, expires = expires,
                    ),
                )
            }
        }
        assertEquals(PlanStatus.INACTIVE, plan.status)
        assertEquals(IntervalUnit.MONTHS, plan.intervalUnit)

        withDb {
            transaction {
                val loaded = assertNotNull(planRepository.get(plan.id))
                assertEquals(PlanStatus.INACTIVE, loaded.status, "the plan-status enum round-trips through its cast")
                assertEquals(IntervalUnit.MONTHS, loaded.intervalUnit, "the interval-unit enum round-trips through its cast")
                assertEquals(Money.of("9.99"), loaded.price)
                assertIs<StandardPlanConfiguration>(loaded.configuration)
                assertNotNull(loaded.expires)
            }
        }
    }

    @Test
    fun `getByKey and getByGroup resolve plans and getByGroup orders by price`() {
        val storeId = storeId()
        lateinit var groupId: UUID
        withDb {
            transaction {
                groupId = planGroupRepository.add(SubscriptionPlanGroup(storeId = storeId, key = "grp", name = "Grp")).id
                planRepository.add(
                    SubscriptionPlan(planGroupId = groupId, storeId = storeId, key = "gold", name = "Gold", price = Money.of("20.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS),
                )
                planRepository.add(
                    SubscriptionPlan(planGroupId = groupId, storeId = storeId, key = "silver", name = "Silver", price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS),
                )
            }
        }

        withDb {
            transaction {
                val byKey = assertNotNull(planRepository.getByKey(storeId, "gold"))
                assertEquals(Money.of("20.00"), byKey.price)
                assertNull(planRepository.getByKey(storeId, "missing"))

                val byGroup = planRepository.getByGroup(groupId)
                assertEquals(listOf("silver", "gold"), byGroup.map { it.key }, "ordered by ascending price")
            }
        }
    }
}
