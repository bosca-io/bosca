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
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
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
 * Real-Postgres tests for the billing-identity chain that the unit suite only mocks: `ecom.accounts`,
 * `ecom.account_addresses`, and the `ecom.account_customers` join. The point is to fire the native-enum
 * casts (`(:type)::ecom.account_type`, `(:type)::ecom.address_type`) and the numeric credit balance
 * against the live DDL — exactly the class of bug (an enum bound without its `::ecom.<type>` cast) that
 * is invisible against a mock.
 */
@OptIn(ExperimentalUuidApi::class)
class AccountRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_account_test")
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
    private val accountAddressRepository = AccountAddressRepositoryImpl()
    private val accountCustomerRepository = AccountCustomerRepositoryImpl()
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
                // Children first to respect the FK graph.
                connection().useStatement("DELETE FROM ecom.account_customers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.account_addresses") { it.execute() }
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
    fun `account add round-trips the account_type enum and numeric credit, then get and getByIds find it`() {
        val companyId = companyId()
        lateinit var business: Account
        lateinit var consumer: Account
        withDb {
            transaction {
                business = accountRepository.add(Account(companyId = companyId, type = AccountType.BUSINESS, credit = Money.of("125.5000")))
                consumer = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER))
            }
        }
        assertEquals(AccountType.BUSINESS, business.type)
        assertEquals(Money.of("125.5000"), business.credit, "numeric(32,4) credit round-trips")
        assertEquals(Money.ZERO, consumer.credit, "default credit is zero")

        withDb {
            transaction {
                val loaded = assertNotNull(accountRepository.get(business.id))
                assertEquals(AccountType.BUSINESS, loaded.type)
                assertEquals(Money.of("125.5000"), loaded.credit)

                val byIds = accountRepository.getByIds(listOf(business.id, consumer.id))
                assertEquals(setOf(business.id, consumer.id), byIds.map { it.id }.toSet())
            }
        }
    }

    @Test
    fun `getForUpdate locks and returns the live row, update writes type and credit, updateProfile leaves credit alone`() {
        val companyId = companyId()
        lateinit var account: Account
        withDb { transaction { account = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER, credit = Money.of("10.0000"))) } }

        withDb { transaction { assertNotNull(accountRepository.getForUpdate(account.id), "for-update returns the live row") } }

        withDb {
            transaction {
                val updated = assertNotNull(accountRepository.update(account.copy(type = AccountType.BUSINESS, credit = Money.of("42.0000"))))
                assertEquals(AccountType.BUSINESS, updated.type)
                assertEquals(Money.of("42.0000"), updated.credit)
            }
        }

        withDb {
            transaction {
                // updateProfile must NOT clobber the contended credit column even if a stale snapshot is passed.
                val profiled = assertNotNull(
                    accountRepository.updateProfile(account.copy(type = AccountType.CONSUMER, credit = Money.of("0.0000"))),
                )
                assertEquals(AccountType.CONSUMER, profiled.type)
                assertEquals(Money.of("42.0000"), profiled.credit, "updateProfile never writes credit")
            }
        }
    }

    @Test
    fun `softDelete hides the account from get and getByCompany`() {
        val companyId = companyId()
        lateinit var keep: Account
        lateinit var drop: Account
        withDb {
            transaction {
                keep = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER))
                drop = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER))
            }
        }

        withDb { transaction { accountRepository.softDelete(drop.id) } }

        withDb {
            transaction {
                assertNull(accountRepository.get(drop.id), "soft-deleted account is invisible")
                assertNotNull(accountRepository.get(keep.id))
                val live = accountRepository.getByCompany(companyId, offset = 0, limit = 50)
                assertEquals(listOf(keep.id), live.map { it.id }, "getByCompany excludes the soft-deleted row")
            }
        }
    }

    @Test
    fun `account_customers join links accounts and customers in both directions`() {
        val companyId = companyId()
        lateinit var account: Account
        lateinit var customerA: Customer
        lateinit var customerB: Customer
        withDb {
            transaction {
                account = accountRepository.add(Account(companyId = companyId, type = AccountType.BUSINESS))
                customerA = customerRepository.add(Customer(companyId = companyId, profileId = UUID.random()))
                customerB = customerRepository.add(Customer(companyId = companyId, profileId = UUID.random()))
                accountCustomerRepository.add(account.id, customerA.id)
                accountCustomerRepository.add(account.id, customerB.id)
                // Replayed join insert is a no-op (on conflict do nothing) — no duplicate, no error.
                accountCustomerRepository.add(account.id, customerA.id)
            }
        }

        withDb {
            transaction {
                val customersOfAccount = customerRepository.getByAccount(account.id)
                assertEquals(setOf(customerA.id, customerB.id), customersOfAccount.map { it.id }.toSet())

                val accountsOfCustomer = accountRepository.getByCustomer(customerA.id)
                assertEquals(listOf(account.id), accountsOfCustomer.map { it.id })
            }
        }
    }

    @Test
    fun `account address add round-trips the address_type enum and getByAccount returns it`() {
        val companyId = companyId()
        lateinit var account: Account
        withDb { transaction { account = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER)) } }

        lateinit var billing: AccountAddress
        withDb {
            transaction {
                billing = accountAddressRepository.add(
                    AccountAddress(
                        accountId = account.id, type = AddressType.BILLING, preferred = true,
                        address1 = "1 Bill St", city = "Arlington", state = "VA", country = "US", zip = "22202", phone = "555-0100",
                        note = "primary",
                    ),
                )
                accountAddressRepository.add(
                    AccountAddress(
                        accountId = account.id, type = AddressType.SHIPPING,
                        address1 = "2 Ship Way", city = "London", state = "LDN", country = "GB", zip = "EC1", phone = "555-0200",
                    ),
                )
            }
        }
        assertEquals(AddressType.BILLING, billing.type)
        assertTrue(billing.preferred)

        withDb {
            transaction {
                val addresses = accountAddressRepository.getByAccount(account.id)
                assertEquals(2, addresses.size)
                assertEquals(setOf(AddressType.BILLING, AddressType.SHIPPING), addresses.map { it.type }.toSet())
                val loadedBilling = addresses.first { it.type == AddressType.BILLING }
                assertEquals("1 Bill St", loadedBilling.address1)
                assertEquals("primary", loadedBilling.note)
                assertTrue(loadedBilling.preferred)
            }
        }
    }
}
