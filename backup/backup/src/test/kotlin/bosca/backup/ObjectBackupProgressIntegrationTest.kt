package bosca.backup

import bosca.backup.ObjectBackupOperation.BACKUP
import bosca.backup.ObjectBackupOperation.RESTORE
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
import kotlin.test.assertIs
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
        val restarted = ObjectBackupProgressServiceImpl(repository)
        val loaded = request { restarted.get(prepared.key) }

        assertEquals(prepared, loaded)
        assertEquals(payload, prepared.payload)
        assertEquals("source-1", prepared.sourceId)
        assertEquals("destination-1", prepared.destinationId)
        assertEquals("content", prepared.scope)
        assertEquals(ObjectBackupProgressStatus.PENDING, prepared.status)
        assertEquals(0, prepared.attempts)
        assertEquals(0L, prepared.version)
        assertEquals(0L, prepared.verifiedBytes)
        assertNull(prepared.error)
    }

    @Test
    fun `persisted lifecycle states are mapped to a closed enum`() = runBlocking {
        val prepared = request { prepare() }
        val running = request { service.start(prepared.key, prepared.version) }
        val failed = request { service.fail(running.key, running.version, "retry required") }
        val retried = request { service.start(failed.key, failed.version) }
        val completed = request { service.complete(retried.key, retried.version) }

        val states = listOf(prepared, running, failed, retried, completed).map {
            assertIs<Enum<*>>(it.status).name
        }
        assertEquals(listOf("PENDING", "RUNNING", "FAILED", "RUNNING", "COMPLETED"), states)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select status from backup.object_progress").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("completed", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun `failed attempts remain visible and retry without resetting identity`() = runBlocking {
        val prepared = request { prepare() }
        val running = request { service.start(prepared.key, prepared.version) }
        val failed = request { service.fail(running.key, running.version, "network unavailable") }

        assertEquals(ObjectBackupProgressStatus.FAILED, failed.status)
        assertEquals(1, failed.attempts)
        assertEquals("network unavailable", request { service.get(failed.key) }?.error)

        val retried = request { service.start(failed.key, failed.version) }
        val completed = request { service.complete(retried.key, retried.version) }

        assertEquals(prepared.key, completed.key)
        assertEquals(prepared.created, completed.created)
        assertEquals(2, completed.attempts)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, completed.status)
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
            request { service.prepare(BACKUP, "source-1", "destination-1", "content", payload.copy(sha256 = "0".repeat(64))) }
        }

        assertEquals(prepared, request { service.get(prepared.key) })
    }

    @Test
    fun `a changed revision leaves earlier completed progress intact`() = runBlocking {
        val completed = request { completed() }
        val changed = request {
            service.prepare(BACKUP, "source-1", "destination-1", "content", payload.copy(revision = "revision-2"))
        }

        assertNotEquals(completed.key, changed.key)
        assertEquals(ObjectBackupProgressStatus.PENDING, changed.status)
        assertEquals(completed, request { service.get(completed.key) })
        assertEquals(2, request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) }.size)
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
        assertEquals(
            ObjectBackupProgressStatus.COMPLETED,
            request { service.complete(retried.key, retried.version) }.status,
        )
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
            service.prepare(BACKUP, "source-1", "destination-1", "content", payload.copy(key = "other-key"))
        }
        request { service.prepare(BACKUP, "other-source", "destination-1", "content", payload) }
        request { service.prepare(BACKUP, "source-1", "other-destination", "content", payload) }
        request { service.prepare(BACKUP, "source-1", "destination-1", "other-scope", payload) }

        assertEquals(listOf(first), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 1) })
        assertEquals(listOf(second), request { service.list(BACKUP, "source-1", "destination-1", "content", 1, 10) })
    }

    @Test
    fun `invalid identities payloads and pagination do not create progress`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(BACKUP, " ", "destination-1", "content", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(BACKUP, "source-1", "", "content", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(BACKUP, "source-1", "destination-1", "", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(BACKUP, "source-1", "destination-1", "content", payload.copy(sha256 = "bad")) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.list(BACKUP, "source-1", "destination-1", "content", -1, 10) }
        }
        assertEquals(emptyList(), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) })
    }

    @Test
    fun `completed output must be explicitly invalidated before repair`() = runBlocking {
        val completed = request { completed() }
        assertFailsWith<IllegalStateException> {
            request { service.start(completed.key, completed.version) }
        }
        val invalid = request { service.invalidate(completed.key, completed.version, "destination object missing") }

        assertEquals(ObjectBackupProgressStatus.FAILED, invalid.status)
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

    @Test
    fun `revision lookup does not create intent and preserves exact existing progress`(): Unit = runBlocking {
        assertNull(request { service.get(BACKUP, "source-1", "destination-1", "content", payload) })
        assertTrue(request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) }.isEmpty())
        val completed = request { completed() }

        assertEquals(completed, request { service.get(BACKUP, "source-1", "destination-1", "content", payload) })
        assertNull(request { service.get(BACKUP, "other-source", "destination-1", "content", payload) })
        assertNull(request { service.get(BACKUP, "source-1", "other-destination", "content", payload) })
        assertNull(request { service.get(BACKUP, "source-1", "destination-1", "other-scope", payload) })
        assertNull(request { service.get(BACKUP, "source-1", "destination-1", "content", payload.copy(revision = "other")) })
        assertEquals(listOf(completed), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) })
    }

    @Test
    fun `revision lookup rejects invalid and conflicting claims without changing persisted intent`(): Unit = runBlocking {
        val prepared = request { prepare() }
        for (changed in listOf(payload.copy(length = 5), payload.copy(payloadPath = "other/path"))) {
            assertFailsWith<IllegalArgumentException> {
                request { service.get(BACKUP, "source-1", "destination-1", "content", changed) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.get(BACKUP, " ", "destination-1", "content", payload) }
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.get(BACKUP, "source-1", "destination-1", "content", payload.copy(sha256 = "bad")) }
        }

        assertEquals(prepared, request { service.get(prepared.key) })
    }

    @Test
    fun `backup and restore checkpoints remain independent for identical payloads`(): Unit = runBlocking {
        val backup = request { service.prepare(BACKUP, "source-1", "destination-1", "content", payload) }
        val restore = request { service.prepare(RESTORE, "source-1", "destination-1", "content", payload) }
        assertNotEquals(backup.key, restore.key)
        val completed = request {
            val running = service.start(backup.key, backup.version)
            service.complete(running.key, running.version)
        }

        assertEquals(completed, request { service.get(BACKUP, "source-1", "destination-1", "content", payload) })
        assertEquals(restore, request { service.get(RESTORE, "source-1", "destination-1", "content", payload) })
        assertEquals(listOf(completed), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) })
        assertEquals(listOf(restore), request { service.list(RESTORE, "source-1", "destination-1", "content", 0, 10) })
    }

    @Test
    fun `digest-free lookup finds only the exact identity without inserting or changing intent`(): Unit = runBlocking {
        val identity = listOf("source-1", "destination-1", "content", payload.store, payload.key, payload.revision)
        assertNull(request { service.get(BACKUP, identity[0], identity[1], identity[2], identity[3], identity[4], identity[5]) })
        assertTrue(request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) }.isEmpty())
        val prepared = request { prepare() }
        assertEquals(prepared, request {
            service.get(BACKUP, identity[0], identity[1], identity[2], identity[3], identity[4], identity[5])
        })
        for (i in identity.indices) {
            val changed = identity.toMutableList().apply { this[i] = "other" }
            assertNull(request { service.get(BACKUP, changed[0], changed[1], changed[2], changed[3], changed[4], changed[5]) })
        }
        assertNull(request { service.get(RESTORE, identity[0], identity[1], identity[2], identity[3], identity[4], identity[5]) })
        assertEquals(prepared, request { service.get(prepared.key) })
    }

    @Test
    fun `digest-free lookup rejects blank identity fields without writing`(): Unit = runBlocking {
        val identity = listOf("source-1", "destination-1", "content", payload.store, payload.key, payload.revision)
        for (i in identity.indices) {
            val changed = identity.toMutableList().apply { this[i] = " " }
            assertFailsWith<IllegalArgumentException> {
                request { service.get(BACKUP, changed[0], changed[1], changed[2], changed[3], changed[4], changed[5]) }
            }
        }
        assertTrue(request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) }.isEmpty())
    }

    @Test
    fun `verified completion and refresh persist provider evidence with guarded versions`(): Unit = runBlocking {
        val completed = request {
            val prepared = prepare()
            val running = service.start(prepared.key, prepared.version)
            service.completeVerified(running.key, running.version, "provider-v1")
        }
        assertEquals("provider-v1", completed.verifiedVersion)
        assertNotNull(completed.verified)
        assertEquals(1, completed.attempts)
        assertEquals(completed, request { service.get(completed.key) })
        val refreshed = request { service.recordVerification(completed.key, completed.version, "provider-v2") }
        assertEquals("provider-v2", refreshed.verifiedVersion)
        assertEquals(completed.attempts, refreshed.attempts)
        assertEquals(completed.version + 1, refreshed.version)
        assertNotNull(refreshed.verified)
        assertFailsWith<IllegalStateException> {
            request { service.recordVerification(completed.key, completed.version, "stale") }
        }
        assertEquals(refreshed, request { service.get(completed.key) })
    }

    @Test
    fun `verification cannot be recorded before completion or with invalid provider identities`(): Unit = runBlocking {
        val prepared = request { prepare() }
        assertFailsWith<IllegalStateException> {
            request { service.recordVerification(prepared.key, prepared.version, "provider-v1") }
        }
        val running = request { service.start(prepared.key, prepared.version) }
        for (value in listOf("", " ", "null")) {
            assertFailsWith<IllegalArgumentException> {
                request { service.completeVerified(running.key, running.version, value) }
            }
        }
        assertEquals(running, request { service.get(running.key) })
        val completed = request { service.completeVerified(running.key, running.version, null) }
        assertNull(completed.verifiedVersion)
        assertNull(completed.verified)
    }

    @Test
    fun `invalidating verified output clears its evidence before another attempt`(): Unit = runBlocking {
        val completed = request {
            val prepared = prepare()
            val running = service.start(prepared.key, prepared.version)
            service.completeVerified(running.key, running.version, "provider-v1")
        }
        val invalid = request { service.invalidate(completed.key, completed.version, "missing") }
        assertNull(invalid.verifiedVersion)
        assertNull(invalid.verified)
        val running = request { service.start(invalid.key, invalid.version) }
        val failed = request { service.fail(running.key, running.version, "failed") }
        assertNull(failed.verifiedVersion)
        assertNull(failed.verified)
    }

    @Test
    fun `a full verification without provider identity clears earlier evidence`() = runBlocking {
        val completed = request {
            val prepared = prepare()
            val running = service.start(prepared.key, prepared.version)
            service.completeVerified(running.key, running.version, "provider-v1")
        }
        val cleared = request { service.recordVerification(completed.key, completed.version, null) }
        assertEquals(ObjectBackupProgressStatus.COMPLETED, cleared.status)
        assertEquals(completed.attempts, cleared.attempts)
        assertNull(cleared.verifiedVersion)
        assertNull(cleared.verified)
    }

    private suspend fun prepare(): ObjectBackupProgress =
        service.prepare(BACKUP, "source-1", "destination-1", "content", payload)

    private suspend fun completed(): ObjectBackupProgress {
        val prepared = prepare()
        val running = service.start(prepared.key, prepared.version)
        return service.complete(running.key, running.version)
    }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)
}
