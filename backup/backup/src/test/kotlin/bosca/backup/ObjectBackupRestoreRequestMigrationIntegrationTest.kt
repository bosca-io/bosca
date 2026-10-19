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
        val before = withConnectionManager { runs.prepareRestore(inventory, "destination", "manifests/historical.json") }
        val checkpoints = withConnectionManager { runs.items(before.key, 0, 20) }
        val migrations = listOf(provide<Migration>(name = "backup-migrations"))
        FlywayMigration(pool).migrate(migrations)
        FlywayMigration(pool).migrate(migrations)
        assertEquals(before, withConnectionManager { runs.get(before.key) })
        assertEquals(checkpoints, withConnectionManager { runs.items(before.key, 0, 20) })
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
