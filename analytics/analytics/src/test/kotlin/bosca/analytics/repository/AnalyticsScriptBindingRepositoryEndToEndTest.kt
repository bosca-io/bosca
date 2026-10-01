@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.analytics.repository

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Integration test for [AnalyticsScriptBindingRepository] against a real PostgreSQL instance. Runs
 * the production `V2__analytics_script_bindings.sql` DDL (loaded from the classpath so the test can
 * never drift from the migration) and exercises the CRUD round trip — which the KSP-generated
 * `@ColumnName` mapping (`script_id` -> `scriptId`) and the enabled/ordinal SQL cannot be verified by
 * an in-memory fake.
 */
class AnalyticsScriptBindingRepositoryEndToEndTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var repository: AnalyticsScriptBindingRepository

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgresContainer.start()

        val url = postgresContainer.jdbcUrl
        val factory = ConnectionFactoryImpl(
            ConnectionConfig(url = url, user = postgresContainer.username, password = postgresContainer.password),
            key = "test",
        )
        connectionPool = ConnectionPool(factory)

        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))
        applyScriptBindingDdl(url)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
        provides<Json>(singleton = true) { testJson }

        repository = AnalyticsScriptBindingRepositoryImpl()
    }

    /** Runs the real V2 migration DDL (self-contained, public-schema) directly against the DB. */
    private fun applyScriptBindingDdl(url: String) {
        val sql = requireNotNull(
            this::class.java.getResource("/db/migrations/V2__analytics_script_bindings.sql"),
        ) { "V2 migration resource not found" }.readText()
        DriverManager.getConnection(url, postgresContainer.username, postgresContainer.password).use { connection ->
            connection.createStatement().use { statement ->
                for (rawStatement in sql.split(";")) {
                    val trimmed = rawStatement.trim()
                    if (trimmed.isNotEmpty()) statement.execute(trimmed)
                }
            }
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withConnection(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        return withContext(cm.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) { cm.release() }
            }
        }
    }

    @Test
    fun `insert persists the row and maps script_id back to scriptId`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            val id = Uuid.random()
            val scriptId = Uuid.random()
            val inserted = repository.insert(id, scriptId, transform = false, enabled = true, ordinal = 4)
            assertNotNull(inserted)
            assertEquals(id, inserted.id)
            assertEquals(scriptId, inserted.scriptId)
            assertEquals(false, inserted.transform)
            assertEquals(true, inserted.enabled)
            assertEquals(4, inserted.ordinal)
            assertNotNull(inserted.created)
            assertNotNull(inserted.modified)

            val fetched = repository.getById(id)
            assertNotNull(fetched)
            assertEquals(scriptId, fetched.scriptId)
            assertEquals(false, fetched.transform)
        }
    }

    @Test
    fun `getEnabled returns only enabled bindings ordered by ordinal`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.insert(Uuid.random(), Uuid.random(), transform = true, enabled = true, ordinal = 2)
            repository.insert(Uuid.random(), Uuid.random(), transform = true, enabled = true, ordinal = 1)
            repository.insert(Uuid.random(), Uuid.random(), transform = true, enabled = false, ordinal = 0)

            val enabled = repository.getEnabled()
            assertEquals(2, enabled.size)
            assertEquals(listOf(1, 2), enabled.map { it.ordinal })
        }
    }

    @Test
    fun `getAll returns enabled and disabled bindings`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            repository.insert(Uuid.random(), Uuid.random(), transform = true, enabled = true, ordinal = 0)
            repository.insert(Uuid.random(), Uuid.random(), transform = true, enabled = false, ordinal = 1)
            assertEquals(2, repository.getAll().size)
        }
    }

    @Test
    fun `update replaces the mutable fields`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            val id = Uuid.random()
            repository.insert(id, Uuid.random(), transform = true, enabled = true, ordinal = 0)
            val newScript = Uuid.random()

            val updated = repository.update(id, newScript, transform = false, enabled = false, ordinal = 9)
            assertNotNull(updated)
            assertEquals(newScript, updated.scriptId)
            assertEquals(false, updated.transform)
            assertEquals(false, updated.enabled)
            assertEquals(9, updated.ordinal)
        }
    }

    @Test
    fun `update returns null for an unknown id`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            assertNull(repository.update(Uuid.random(), Uuid.random(), transform = true, enabled = true, ordinal = 0))
        }
    }

    @Test
    fun `delete removes the row`() = runTest(timeout = Duration.parse("60s")) {
        withConnection {
            val id = Uuid.random()
            repository.insert(id, Uuid.random(), transform = true, enabled = true, ordinal = 0)
            repository.delete(id)
            assertNull(repository.getById(id))
        }
    }
}
