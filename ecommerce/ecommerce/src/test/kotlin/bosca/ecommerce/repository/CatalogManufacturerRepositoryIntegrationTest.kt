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
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * End-to-end repository tests for catalogs and manufacturers against a real PostgreSQL via
 * Testcontainers. Unlike the mockk service tests, this executes the KSP-generated `@Query` SQL —
 * pinning the schema-qualified `ecom.*` table/column names, the `(company_id, key)` unique
 * constraint, and the `extras::jsonb` round-trip. Doubles as a migration smoke test (Flyway applies
 * V1 before any assertion).
 */
@OptIn(ExperimentalUuidApi::class)
class CatalogManufacturerRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val companyRepository = CompanyRepositoryImpl()
    private val catalogRepository = CatalogRepositoryImpl()
    private val manufacturerRepository = ManufacturerRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
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

    private fun newCompany(): UUID {
        lateinit var id: UUID
        withDb {
            transaction {
                id = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
            }
        }
        return id
    }

    @Test
    fun `catalog CRUD round-trips and lists by company`() {
        val companyId = newCompany()
        lateinit var created: Catalog
        withDb { transaction { created = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")) } }

        assertEquals("default", created.key)
        assertEquals(companyId, created.companyId)

        var fetched: Catalog? = null
        var byKey: Catalog? = null
        var byCompany: List<Catalog> = emptyList()
        withDb {
            transaction {
                fetched = catalogRepository.get(created.id)
                byKey = catalogRepository.getByKey(companyId, "default")
                byCompany = catalogRepository.getByCompany(companyId)
            }
        }
        assertEquals(created.id, fetched?.id)
        assertEquals(created.id, byKey?.id)
        assertEquals(1, byCompany.size)

        var edited: Catalog? = null
        withDb { transaction { edited = catalogRepository.update(created.copy(name = "Renamed")) } }
        assertEquals("Renamed", edited?.name)
    }

    @Test
    fun `catalog key is unique per company`() {
        val companyId = newCompany()
        withDb { transaction { catalogRepository.add(Catalog(companyId = companyId, key = "dup", name = "First")) } }

        assertFailsWith<Exception> {
            withDb { transaction { catalogRepository.add(Catalog(companyId = companyId, key = "dup", name = "Second")) } }
        }

        // A different company may reuse the same key.
        val other = newCompany()
        var ok: Catalog? = null
        withDb { transaction { ok = catalogRepository.add(Catalog(companyId = other, key = "dup", name = "Other")) } }
        assertNotNull(ok)
    }

    @Test
    fun `manufacturer persists with a jsonb extras round-trip`() {
        val companyId = newCompany()
        val extras = EmptyManufacturerExtras
        lateinit var created: Manufacturer
        withDb { transaction { created = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme", extras = extras)) } }

        var fetched: Manufacturer? = null
        var byCompany: List<Manufacturer> = emptyList()
        withDb {
            transaction {
                fetched = manufacturerRepository.get(created.id)
                byCompany = manufacturerRepository.getByCompany(companyId, 0, 25)
            }
        }
        assertEquals("Acme", fetched?.name)
        assertEquals(extras, fetched?.extras)
        assertEquals(1, byCompany.size)
    }

    @Test
    fun `soft-deleted-style absence returns null for a missing catalog`() {
        var missing: Catalog? = null
        withDb { transaction { missing = catalogRepository.get(UUID.random()) } }
        assertNull(missing)
    }
}
