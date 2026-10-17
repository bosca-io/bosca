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
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Upgrades pre-claim databases without selecting a winner for conflicting retained payloads. */
@OptIn(InternalDI::class)
class ObjectBackupPayloadClaimMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var migration: Migration
    private val payload = BackupObject("content", "items/a", "revision-1", 4, "a".repeat(64), "objects/shared")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_payload_claim_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-payload-claim-upgrade-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        migration = provide(name = "backup-migrations")
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = listOf(
                "V1__backup_object_progress.sql",
                "V2__backup_object_runs.sql",
                "V3__object_backup_operations.sql",
            )
        }))
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `upgrade backfills matching backup claims and preserves completed progress exactly`(): Unit = runBlocking {
        seed("a", payload)
        seed("b", payload.copy(store = "media", key = "other", revision = "other"), source = "source-2", scope = "other")
        seed("c", payload.copy(length = 8, sha256 = "b".repeat(64)), destination = "destination-2")
        val service: ObjectBackupProgressService = provide()
        val before = withConnectionManager { assertNotNull(service.get("a".repeat(64))) }

        FlywayMigration(pool).migrate(listOf(migration))
        FlywayMigration(pool).migrate(listOf(migration))

        assertEquals(before, withConnectionManager { service.get(before.key) })
        assertEquals(2, queryInt("select count(*) from backup.object_paths"))
        assertEquals(1, queryInt("""
            select count(*) from backup.object_paths
            where destination_id = 'destination-1' and path = 'objects/shared'
                and length = 4 and sha256 = '${payload.sha256}'
        """.trimIndent()))
        val conflict = payload.copy(key = "later", sha256 = "c".repeat(64))
        assertFailsWith<IllegalArgumentException> {
            withConnectionManager { service.prepare(ObjectBackupOperation.BACKUP, "later-source", "destination-1", "later", conflict) }
        }
    }

    @Test
    fun `upgrade excludes restore target paths from immutable retained claims`(): Unit = runBlocking {
        seed("a", payload)
        seed("b", payload.copy(length = 8, sha256 = "b".repeat(64)), operation = "restore")
        seed("c", payload.copy(payloadPath = "objects/restore-only"), operation = "restore")
        seedRun("f", operation = "restore")

        FlywayMigration(pool).migrate(listOf(migration))

        assertEquals(1, queryInt("select count(*) from backup.object_paths"))
        assertEquals(3, queryInt("select count(*) from backup.object_progress"))
    }

    @Test
    fun `upgrade reserves existing backup manifest paths and preserves their runs`(): Unit = runBlocking {
        seed("a", payload)
        seedRun("f")
        val service: ObjectBackupRunService = provide()
        val before = withConnectionManager { assertNotNull(service.get("f".repeat(64))) }

        FlywayMigration(pool).migrate(listOf(migration))

        assertEquals(before, withConnectionManager { service.get(before.key) })
        assertEquals(2, queryInt("select count(*) from backup.object_paths"))
        val progress: ObjectBackupProgressService = provide()
        assertFailsWith<IllegalArgumentException> {
            withConnectionManager {
                progress.prepare(
                    ObjectBackupOperation.BACKUP, "other", "destination-1", "other",
                    payload.copy(key = "other", payloadPath = before.path),
                )
            }
        }
    }

    @Test
    fun `upgrade rejects historical payload and manifest path overlap`(): Unit = runBlocking {
        seed("a", payload.copy(payloadPath = "manifests/legacy.json"))
        seedRun("f")

        val failure = assertFailsWith<Exception> { FlywayMigration(pool).migrate(listOf(migration)) }

        assertTrue(failure.stackTraceToString().contains("Conflicting retained backup path"))
        assertEquals(1, queryInt("select count(*) from backup.object_progress"))
        assertEquals(1, queryInt("select count(*) from backup.object_runs"))
    }

    @Test
    fun `upgrade rejects different manifests claiming the same destination path`(): Unit = runBlocking {
        seedRun("e")
        seedRun("f")

        val failure = assertFailsWith<Exception> { FlywayMigration(pool).migrate(listOf(migration)) }

        assertTrue(failure.stackTraceToString().contains("Conflicting retained backup path"))
        assertEquals(2, queryInt("select count(*) from backup.object_runs"))
    }

    @Test
    fun `upgrade rejects conflicting historical digests without discarding either claim`(): Unit = runBlocking {
        rejectsConflict(payload.copy(sha256 = "b".repeat(64)))
    }

    @Test
    fun `upgrade rejects conflicting historical lengths even when digests match`(): Unit = runBlocking {
        rejectsConflict(payload.copy(length = payload.length + 1))
    }

    @Test
    fun `database rejects unclaimed or conflicting backup rows and deletion of referenced claims`(): Unit = runBlocking {
        seed("a", payload)
        FlywayMigration(pool).migrate(listOf(migration))

        assertFailsWith<java.sql.SQLException> { seed("b", payload.copy(payloadPath = "objects/unclaimed")) }
        assertFailsWith<java.sql.SQLException> { seed("c", payload.copy(length = 8)) }
        assertFailsWith<java.sql.SQLException> { seed("d", payload.copy(sha256 = "d".repeat(64))) }
        assertFailsWith<java.sql.SQLException> { seedRun("f") }
        assertFailsWith<java.sql.SQLException> {
            jdbc { connection ->
                connection.createStatement().use { it.executeUpdate("delete from backup.object_paths") }
            }
        }
        seed("e", payload.copy(key = "matching"))

        assertEquals(2, queryInt("select count(*) from backup.object_progress"))
        assertEquals(1, queryInt("select count(*) from backup.object_paths"))
    }

    private suspend fun rejectsConflict(conflict: BackupObject) {
        seed("a", payload)
        seed("b", conflict.copy(key = "other"), source = "source-2", scope = "other")

        val failure = assertFailsWith<Exception> { FlywayMigration(pool).migrate(listOf(migration)) }

        assertTrue(failure.stackTraceToString().contains("Conflicting retained backup payload"), failure.toString())
        assertEquals(2, queryInt("select count(*) from backup.object_progress"))
        jdbc { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select to_regclass('backup.object_paths') is not null").use { rows ->
                    assertTrue(rows.next())
                    assertFalse(rows.getBoolean(1))
                }
            }
        }
    }

    private fun seed(
        key: String,
        value: BackupObject,
        source: String = "source-1",
        destination: String = "destination-1",
        scope: String = "content",
        operation: String = "backup",
    ) {
        jdbc { connection ->
            connection.prepareStatement("""
                insert into backup.object_progress
                    (key, source_id, destination_id, scope, operation, payload, status, attempts, verified_bytes, version)
                values (?, ?, ?, ?, ?, ?::jsonb, 'completed', 1, ?, 2)
            """.trimIndent()).use { statement ->
                statement.setString(1, key.repeat(64))
                statement.setString(2, source)
                statement.setString(3, destination)
                statement.setString(4, scope)
                statement.setString(5, operation)
                statement.setString(6, Json.encodeToString(BackupObject.serializer(), value))
                statement.setLong(7, value.length)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun seedRun(key: String, operation: String = "backup") {
        val inventory = ObjectBackupManifest("legacy-$key", "source-1", "content", "2026-10-16T00:00:00Z", listOf(payload))
        jdbc { connection ->
            connection.prepareStatement("""
                insert into backup.object_runs (key, source_id, destination_id, scope, operation, inventory, path)
                values (?, 'source-1', 'destination-1', 'content', ?, ?::jsonb, 'manifests/legacy.json')
            """.trimIndent()).use { statement ->
                statement.setString(1, key.repeat(64))
                statement.setString(2, operation)
                statement.setString(3, Json.encodeToString(ObjectBackupManifest.serializer(), inventory))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun queryInt(sql: String): Int = jdbc { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)
}
