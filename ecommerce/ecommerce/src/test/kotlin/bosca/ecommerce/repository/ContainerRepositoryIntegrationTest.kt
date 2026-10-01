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
import bosca.ecommerce.model.Container
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
 * Real-Postgres tests for `ecom.shipping_containers` — the company box catalog the packer fills,
 * only mocked in the unit suite. Covers add/get/getByCompany/update/soft-delete plus the full set of
 * outer-dimension and `supported_*` inner-capacity columns round-tripping as doubles.
 */
@OptIn(ExperimentalUuidApi::class)
class ContainerRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_container_test")
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
    private val containerRepository = ContainerRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.shipping_containers") { it.execute() }
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

    private fun container(companyId: UUID, name: String) = Container(
        companyId = companyId, name = name,
        width = 12.0, height = 8.0, length = 6.0, weight = 0.5,
        supportedWidth = 11.5, supportedHeight = 7.5, supportedLength = 5.5, supportedWeight = 25.0,
    )

    @Test
    fun `add round-trips all dimension columns and get finds the container`() {
        val companyId = companyId()
        lateinit var box: Container
        withDb { transaction { box = containerRepository.add(container(companyId, "Medium Box")) } }

        withDb {
            transaction {
                val loaded = assertNotNull(containerRepository.get(box.id))
                assertEquals("Medium Box", loaded.name)
                assertEquals(12.0, loaded.width)
                assertEquals(8.0, loaded.height)
                assertEquals(6.0, loaded.length)
                assertEquals(0.5, loaded.weight)
                assertEquals(11.5, loaded.supportedWidth)
                assertEquals(7.5, loaded.supportedHeight)
                assertEquals(5.5, loaded.supportedLength)
                assertEquals(25.0, loaded.supportedWeight)
            }
        }
    }

    @Test
    fun `getByCompany lists live containers ordered by name and excludes soft-deleted`() {
        val companyId = companyId()
        lateinit var small: Container
        lateinit var large: Container
        lateinit var retired: Container
        withDb {
            transaction {
                large = containerRepository.add(container(companyId, "Large"))
                small = containerRepository.add(container(companyId, "Small"))
                retired = containerRepository.add(container(companyId, "Retired"))
            }
        }

        withDb { transaction { containerRepository.softDelete(retired.id) } }

        withDb {
            transaction {
                assertNull(containerRepository.get(retired.id), "soft-deleted container is invisible")
                val live = containerRepository.getByCompany(companyId)
                assertEquals(listOf("Large", "Small"), live.map { it.name }, "ordered by name, soft-deleted excluded")
                assertEquals(setOf(large.id, small.id), live.map { it.id }.toSet())
            }
        }
    }

    @Test
    fun `update rewrites dimensions in place`() {
        val companyId = companyId()
        lateinit var box: Container
        withDb { transaction { box = containerRepository.add(container(companyId, "Box")) } }

        withDb {
            transaction {
                val updated = assertNotNull(
                    containerRepository.update(box.copy(name = "Box XL", width = 20.0, supportedWeight = 50.0)),
                )
                assertEquals("Box XL", updated.name)
                assertEquals(20.0, updated.width)
                assertEquals(50.0, updated.supportedWeight)
                assertEquals(8.0, updated.height, "unchanged dimensions persist")
            }
        }
    }
}
