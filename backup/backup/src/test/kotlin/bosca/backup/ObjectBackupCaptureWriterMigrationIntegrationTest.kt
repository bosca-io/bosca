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
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Existing run ownership survives the addition of token-fenced capture writers. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureWriterMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("capture_writer_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), key = "capture-writer-upgrade",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V12__") }
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
    fun `capture writer migration is registered`() {
        assertTrue("V12__object_backup_capture_writers.sql" in BackupMigration().resources)
    }

    @Test
    fun `upgrade preserves existing run claims and enforces exactly one referenced owner`(): Unit = runBlocking {
        val inventory = ObjectBackupManifest("saved", "source", "content", "2026-10-18T00:00:00Z", emptyList())
        val repository = provide<ObjectBackupRunRepository>()
        val run = ObjectBackupRun("existing-run", "source", "retained", "content", ObjectBackupOperation.BACKUP,
            inventory, "manifests/saved.json")
        val before = withConnectionManager {
            provide<ObjectBackupProgressService>().reserveManifest(run.destinationId, run.path, run.key)
            assertEquals(1, repository.add(run))
            checkNotNull(repository.start(run.key, 0))
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("insert into backup.object_stores (key, type, base_path) values ('retained', 'fs', '/retained'), ('other', 'fs', '/other')")
                sql.execute("insert into backup.object_store_writers (storage_key, run_key) values ('retained', 'existing-run')")
                sql.execute("insert into backup.object_capture_plans (key, destination_id, inventory, payload_prefix, path) values ('plan', 'other', '{}', 'payloads', 'manifest.json')")
            }
        }
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        assertEquals(before, withConnectionManager { repository.get(run.key) })
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { sql ->
                sql.executeQuery("select column_name from information_schema.columns where table_schema = 'backup' and table_name = 'object_store_writers'").use { rows ->
                    val columns = buildSet { while (rows.next()) add(rows.getString(1)) }
                    assertTrue(columns.containsAll(setOf("capture_key", "capture_token")), "Capture owner columns must exist")
                }
                sql.executeQuery("select run_key, capture_key, capture_token from backup.object_store_writers").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("existing-run", rows.getString(1))
                    assertEquals(null, rows.getString(2))
                    assertEquals(null, rows.getString(3))
                    assertEquals(false, rows.next())
                }
                for (values in listOf(
                    "'other', null, null, null",
                    "'other', 'existing-run', 'plan', 'token'",
                    "'other', null, 'plan', null",
                    "'other', null, null, 'token'",
                    "'other', 'existing-run', null, 'token'",
                    "'other', null, 'plan', ' '",
                    "'other', null, 'missing-plan', 'token'",
                    "'missing-store', null, 'plan', 'token'",
                )) {
                    assertFailsWith<SQLException>(values) {
                        sql.execute("insert into backup.object_store_writers (storage_key, run_key, capture_key, capture_token) values ($values)")
                    }
                }
                sql.execute("insert into backup.object_store_writers (storage_key, capture_key, capture_token) values ('other', 'plan', 'token')")
                assertFailsWith<SQLException> { sql.execute("delete from backup.object_capture_plans where key = 'plan'") }
                assertFailsWith<SQLException> { sql.execute("delete from backup.object_stores where key = 'other'") }
                assertFailsWith<SQLException> { sql.execute("delete from backup.object_runs where key = 'existing-run'") }
            }
        }
    }
}
