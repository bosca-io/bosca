package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedPostgreSQLContainer
import java.sql.DriverManager
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Adds request identities without inventing selections for historical restore runs. */
@OptIn(InternalDI::class)
class ObjectBackupRestoreRequestMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("restore_request_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), key = "restore-request-upgrade-test",
        ))
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        provides<BoscaApplication>(singleton = true) { application }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V14__") }
        }))
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `registered upgrade preserves historical restore intent and checkpoints without request rows`(): Unit = runBlocking {
        val runs = provide<ObjectBackupRunService>()
        val inventory = ObjectBackupManifest("historical", "source", "content", "2026-10-18T00:00:00Z",
            listOf(BackupObject("content", "asset", "v1", 4, "a".repeat(64), "payloads/asset")))
        val before = ObjectBackupRun("historical-run", "source", "destination", "content", ObjectBackupOperation.RESTORE,
            inventory, "manifests/historical.json", created = OffsetDateTime.parse("2026-10-20T00:00:00Z"))
        val checkpoint = ObjectBackupProgress("historical-checkpoint", "source", "destination", "content",
            ObjectBackupOperation.RESTORE, inventory.objects.single().copy(payloadPath = "asset"), created = before.created)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("""
                insert into backup.object_runs (key, source_id, destination_id, scope, operation, inventory, path, created, modified)
                values (?, 'source', 'destination', 'content', 'restore', ?::jsonb, ?, ?, ?)
            """.trimIndent()).use {
                it.setString(1, before.key)
                it.setString(2, Json.encodeToString(ObjectBackupManifest.serializer(), inventory))
                it.setString(3, before.path)
                it.setObject(4, before.created)
                it.setObject(5, before.modified)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_progress (key, source_id, destination_id, scope, operation, payload, created, modified)
                values (?, 'source', 'destination', 'content', 'restore', ?::jsonb, ?, ?)
            """.trimIndent()).use {
                it.setString(1, checkpoint.key)
                it.setString(2, Json.encodeToString(BackupObject.serializer(), checkpoint.payload))
                it.setObject(3, checkpoint.created)
                it.setObject(4, checkpoint.modified)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("insert into backup.object_run_items (run_key, position, progress_key) values (?, 0, ?)").use {
                it.setString(1, before.key)
                it.setString(2, checkpoint.key)
                assertEquals(1, it.executeUpdate())
            }
        }
        val migrations = listOf(provide<Migration>(name = "backup-migrations"))
        FlywayMigration(pool).migrate(migrations)
        FlywayMigration(pool).migrate(migrations)
        assertEquals(before, withConnectionManager { runs.get(before.key) })
        assertEquals(listOf(checkpoint), withConnectionManager { runs.items(before.key, 0, 20) })
        assertNull(withConnectionManager { provide<ObjectBackupRunRepository>().getRestoreRequest("unknown") })
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from backup.object_restore_requests").use {
                    it.next()
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }
}
