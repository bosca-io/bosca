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
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.Money
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Real-Postgres proof of the company-credit lifecycle: the newly added `update` and
 * `softDelete` queries, plus the `updateBalance` spend path. Confirms `number`+`paid` immutability
 * (the natural redeemable key and total-spent are NOT in `update`'s SET list) and that a soft-deleted
 * credit disappears from both `get` and `getByNumber`. The unit suite mocks this repository, so these
 * balance/limit mutations against the `numeric(32,4)` columns are exercised HERE against live SQL.
 */
@OptIn(ExperimentalUuidApi::class)
class CompanyCreditRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_credit_test")
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
    private val companyCreditRepository = CompanyCreditRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.company_credits") { it.execute() }
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
        lateinit var companyId: UUID
        withDb { transaction { companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id } }
        return companyId
    }

    @Test
    fun `add then get getByNumber and getByCompany return the credit`() {
        val companyId = companyId()
        lateinit var created: CompanyCredit
        withDb {
            transaction {
                created = companyCreditRepository.add(
                    CompanyCredit(companyId = companyId, number = "GC-0001", description = "welcome", balance = Money.of("50.00")),
                )
            }
        }
        assertEquals(Money.of("50.00"), created.balance)

        var byId: CompanyCredit? = null
        var byNumber: CompanyCredit? = null
        var byCompany: List<CompanyCredit> = emptyList()
        withDb {
            transaction {
                byId = companyCreditRepository.get(created.id)
                byNumber = companyCreditRepository.getByNumber("GC-0001")
                byCompany = companyCreditRepository.getByCompany(companyId, 0, 10)
            }
        }
        assertEquals(created.id, byId?.id)
        assertEquals(created.id, byNumber?.id)
        assertEquals("GC-0001", byNumber?.number)
        assertEquals(1, byCompany.size)
        assertEquals(created.id, byCompany.first().id)
    }

    @Test
    fun `update mutates editable fields but leaves number and paid immutable`() {
        val companyId = companyId()
        lateinit var created: CompanyCredit
        withDb {
            transaction {
                created = companyCreditRepository.add(
                    CompanyCredit(companyId = companyId, number = "GC-0002", description = "orig", balance = Money.of("100.00"), paid = Money.of("0.00")),
                )
            }
        }

        var updated: CompanyCredit? = null
        withDb {
            transaction {
                // The model carries a different number+paid, but update's SET list must NOT touch them.
                updated = companyCreditRepository.update(
                    created.copy(number = "GC-IGNORED", description = "edited", balance = Money.of("75.00"), paid = Money.of("999.00")),
                )
            }
        }
        val u = requireNotNull(updated)
        assertEquals("edited", u.description, "description is editable")
        assertEquals(Money.of("75.00"), u.balance, "balance is editable")
        assertEquals("GC-0002", u.number, "number is the immutable natural key — update must not change it")
        assertEquals(Money.of("0.00"), u.paid, "paid is total-spent — update must not change it")
    }

    @Test
    fun `updateBalance records a spend by lowering balance and raising paid`() {
        val companyId = companyId()
        lateinit var created: CompanyCredit
        withDb {
            transaction {
                created = companyCreditRepository.add(
                    CompanyCredit(companyId = companyId, number = "GC-0003", balance = Money.of("40.00"), paid = Money.of("10.00")),
                )
            }
        }

        var updated: CompanyCredit? = null
        withDb {
            transaction {
                // Spend $15: balance 40 -> 25, paid 10 -> 25.
                updated = companyCreditRepository.updateBalance(created.copy(balance = Money.of("25.00"), paid = Money.of("25.00")))
            }
        }
        val u = requireNotNull(updated)
        assertEquals(Money.of("25.00"), u.balance)
        assertEquals(Money.of("25.00"), u.paid)
    }

    @Test
    fun `softDelete makes both get and getByNumber return null`() {
        val companyId = companyId()
        lateinit var created: CompanyCredit
        withDb {
            transaction {
                created = companyCreditRepository.add(CompanyCredit(companyId = companyId, number = "GC-0004", balance = Money.of("5.00")))
            }
        }

        withDb { transaction { companyCreditRepository.softDelete(created.id) } }

        var byId: CompanyCredit? = created
        var byNumber: CompanyCredit? = created
        withDb {
            transaction {
                byId = companyCreditRepository.get(created.id)
                byNumber = companyCreditRepository.getByNumber("GC-0004")
            }
        }
        assertNull(byId, "a soft-deleted credit must not be returned by get")
        assertNull(byNumber, "a soft-deleted credit must not be returned by getByNumber")
    }
}
