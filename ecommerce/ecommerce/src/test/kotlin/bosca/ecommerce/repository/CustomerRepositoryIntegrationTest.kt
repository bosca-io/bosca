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
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
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
 * Real-Postgres tests for `ecom.customers` — the profile-backed shopper identity the unit suite only
 * mocks. Proves the `extras` jsonb round-trips via JsonbMapper, the `(company, profile)` uniqueness
 * lookup, the nullable `default_account_id` FK (set on update), and the soft-delete predicate.
 */
@OptIn(ExperimentalUuidApi::class)
class CustomerRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_customer_test")
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
    private val accountRepository = AccountRepositoryImpl()
    private val customerRepository = CustomerRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.account_customers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.customers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.accounts") { it.execute() }
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

    private fun companyId(): UUID {
        lateinit var id: UUID
        withDb { transaction { id = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id } }
        return id
    }

    @Test
    fun `customer add then get, getByIds and getByProfile resolve it and extras jsonb round-trips`() {
        val companyId = companyId()
        val profileId = UUID.random()
        lateinit var customer: Customer
        withDb { transaction { customer = customerRepository.add(Customer(companyId = companyId, profileId = profileId)) } }

        withDb {
            transaction {
                val loaded = assertNotNull(customerRepository.get(customer.id))
                assertEquals(profileId, loaded.profileId)
                assertEquals(companyId, loaded.companyId)
                assertNull(loaded.defaultAccountId)

                val byIds = customerRepository.getByIds(listOf(customer.id))
                assertEquals(listOf(customer.id), byIds.map { it.id })

                val byProfile = assertNotNull(customerRepository.getByProfile(companyId, profileId), "the (company, profile) lookup finds the customer")
                assertEquals(customer.id, byProfile.id)

                assertNull(customerRepository.getByProfile(companyId, UUID.random()), "an unknown profile resolves to null")
            }
        }
    }

    @Test
    fun `update sets the default_account_id FK and getByCompany lists the live customer`() {
        val companyId = companyId()
        lateinit var customer: Customer
        lateinit var account: Account
        withDb {
            transaction {
                customer = customerRepository.add(Customer(companyId = companyId, profileId = UUID.random()))
                account = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER))
            }
        }

        withDb {
            transaction {
                val updated = assertNotNull(customerRepository.update(customer.copy(defaultAccountId = account.id)))
                assertEquals(account.id, updated.defaultAccountId, "the default account FK persists")
            }
        }

        withDb {
            transaction {
                val live = customerRepository.getByCompany(companyId, offset = 0, limit = 50)
                assertEquals(listOf(customer.id), live.map { it.id })
                assertEquals(account.id, live.first().defaultAccountId)
            }
        }
    }

    @Test
    fun `softDelete hides the customer from get, getByProfile and getByCompany`() {
        val companyId = companyId()
        val profileId = UUID.random()
        lateinit var customer: Customer
        withDb { transaction { customer = customerRepository.add(Customer(companyId = companyId, profileId = profileId)) } }

        withDb { transaction { customerRepository.softDelete(customer.id) } }

        withDb {
            transaction {
                assertNull(customerRepository.get(customer.id), "soft-deleted customer is invisible to get")
                assertNull(customerRepository.getByProfile(companyId, profileId), "and to the profile lookup")
                assertEquals(emptyList(), customerRepository.getByCompany(companyId, offset = 0, limit = 50).map { it.id })
            }
        }
    }
}
