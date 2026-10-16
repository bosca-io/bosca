package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the registered migration, DI providers and generated repository against real PostgreSQL. */
@OptIn(InternalDI::class)
class ObjectBackupProgressIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var service: ObjectBackupProgressService
    private lateinit var repository: ObjectBackupProgressRepository
    private lateinit var migration: Migration
    private val payload = BackupObject(
        store = "content",
        key = "metadata/item/1/content",
        revision = "revision-1",
        length = 4,
        sha256 = "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7",
        payloadPath = "objects/revision-1",
    )

    @BeforeTest
    fun setUp() = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_progress").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-progress-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        migration = provide<Migration>(name = "backup-migrations")
        FlywayMigration(pool).migrate(listOf(migration))
        service = provide()
        repository = provide()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `registered migration is repeatable and creates the progress table`() = runBlocking {
        FlywayMigration(pool).migrate(listOf(migration))

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select to_regclass('backup.object_progress') is not null").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                }
            }
        }
    }

    @Test
    fun `prepared intent survives a service restart with exact JSON payload mapping`() = runBlocking {
        val prepared = request { prepare() }
        val restarted = ObjectBackupProgressService(repository)
        val loaded = request { restarted.get(prepared.key) }

        assertEquals(prepared, loaded)
        assertEquals(payload, prepared.payload)
        assertEquals("source-1", prepared.sourceId)
        assertEquals("destination-1", prepared.destinationId)
        assertEquals("content", prepared.scope)
        assertEquals("pending", prepared.status)
        assertEquals(0, prepared.attempts)
        assertEquals(0L, prepared.version)
        assertEquals(0L, prepared.verifiedBytes)
        assertNull(prepared.error)
    }

    @Test
    fun `failed attempts remain visible and retry without resetting identity`() = runBlocking {
        val prepared = request { prepare() }
        val running = request { service.start(prepared.key, prepared.version) }
        val failed = request { service.fail(running.key, running.version, "network unavailable") }

        assertEquals("failed", failed.status)
        assertEquals(1, failed.attempts)
        assertEquals("network unavailable", request { service.get(failed.key) }?.error)

        val retried = request { service.start(failed.key, failed.version) }
        val completed = request { service.complete(retried.key, retried.version) }

        assertEquals(prepared.key, completed.key)
        assertEquals(prepared.created, completed.created)
        assertEquals(2, completed.attempts)
        assertEquals("completed", completed.status)
        assertEquals(payload.length, completed.verifiedBytes)
        assertNull(completed.error)
        assertTrue(completed.version > failed.version)
    }

    @Test
    fun `duplicate preparation preserves completed progress`() = runBlocking {
        val completed = request { completed() }

        assertEquals(completed, request { prepare() })
    }

    @Test
    fun `conflicting payload claims cannot change existing revision intent`() = runBlocking {
        val prepared = request { prepare() }

        assertFailsWith<IllegalArgumentException> {
            request { service.prepare("source-1", "destination-1", "content", payload.copy(sha256 = "0".repeat(64))) }
        }

        assertEquals(prepared, request { service.get(prepared.key) })
    }

    @Test
    fun `a changed revision leaves earlier completed progress intact`() = runBlocking {
        val completed = request { completed() }
        val changed = request {
            service.prepare("source-1", "destination-1", "content", payload.copy(revision = "revision-2"))
        }

        assertNotEquals(completed.key, changed.key)
        assertEquals("pending", changed.status)
        assertEquals(completed, request { service.get(completed.key) })
        assertEquals(2, request { service.list("source-1", "destination-1", "content", 0, 10) }.size)
    }

    @Test
    fun `stale acknowledgements cannot complete a newer attempt`() = runBlocking {
        val prepared = request { prepare() }
        val first = request { service.start(prepared.key, prepared.version) }
        val failed = request { service.fail(first.key, first.version, "worker stopped") }
        val retried = request { service.start(failed.key, failed.version) }

        assertFailsWith<IllegalStateException> {
            request { service.complete(first.key, first.version) }
        }

        assertEquals(retried, request { service.get(retried.key) })
        assertEquals("completed", request { service.complete(retried.key, retried.version) }.status)
    }

    @Test
    fun `only one independent requester can start the same observed version`() = runBlocking {
        val prepared = request { prepare() }
        val attempts = coroutineScope {
            List(2) {
                async { request { repository.start(prepared.key, prepared.version) } }
            }.awaitAll()
        }

        assertEquals(1, attempts.count { it != null })
        assertEquals(1, request { service.get(prepared.key) }?.attempts)
    }

    @Test
    fun `lists isolate source destination and scope with stable pagination`() = runBlocking {
        val first = request { prepare() }
        val second = request {
            service.prepare("source-1", "destination-1", "content", payload.copy(key = "other-key"))
        }
        request { service.prepare("other-source", "destination-1", "content", payload) }
        request { service.prepare("source-1", "other-destination", "content", payload) }
        request { service.prepare("source-1", "destination-1", "other-scope", payload) }

        assertEquals(listOf(first), request { service.list("source-1", "destination-1", "content", 0, 1) })
        assertEquals(listOf(second), request { service.list("source-1", "destination-1", "content", 1, 10) })
    }

    @Test
    fun `invalid identities payloads and pagination do not create progress`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(" ", "destination-1", "content", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare("source-1", "", "content", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare("source-1", "destination-1", "", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare("source-1", "destination-1", "content", payload.copy(sha256 = "bad")) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.list("source-1", "destination-1", "content", -1, 10) }
        }
        assertEquals(emptyList(), request { service.list("source-1", "destination-1", "content", 0, 10) })
    }

    @Test
    fun `completed output must be explicitly invalidated before repair`() = runBlocking {
        val completed = request { completed() }
        assertFailsWith<IllegalStateException> {
            request { service.start(completed.key, completed.version) }
        }
        val invalid = request { service.invalidate(completed.key, completed.version, "destination object missing") }

        assertEquals("failed", invalid.status)
        assertEquals(0L, invalid.verifiedBytes)
        assertEquals("destination object missing", invalid.error)
        assertEquals(2, request { service.start(invalid.key, invalid.version) }.attempts)
    }

    @Test
    fun `pending state cannot be acknowledged as completed or failed`() = runBlocking {
        val prepared = request { prepare() }
        assertFailsWith<IllegalStateException> {
            request { service.complete(prepared.key, prepared.version) }
        }
        assertFailsWith<IllegalStateException> {
            request { service.fail(prepared.key, prepared.version, "not started") }
        }
        assertNotNull(request { service.get(prepared.key) })
        assertEquals(prepared, request { service.get(prepared.key) })
    }

    private suspend fun prepare(): ObjectBackupProgress =
        service.prepare("source-1", "destination-1", "content", payload)

    private suspend fun completed(): ObjectBackupProgress {
        val prepared = prepare()
        val running = service.start(prepared.key, prepared.version)
        return service.complete(running.key, running.version)
    }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)
}
