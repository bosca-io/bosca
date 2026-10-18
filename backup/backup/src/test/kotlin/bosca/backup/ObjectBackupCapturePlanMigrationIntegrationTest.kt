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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Registered plan migration preserves historical object progress without inventing source selections. */
@OptIn(InternalDI::class)
class ObjectBackupCapturePlanMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture_plan_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), key = "capture-plan-upgrade-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V10__") }
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
    fun `capture plan migration is registered by its owner`() {
        assertTrue("V10__object_backup_capture_plans.sql" in BackupMigration().resources)
    }

    @Test
    fun `upgrade creates empty durable plans and preserves old completed progress exactly`(): Unit = runBlocking {
        val progress = provide<ObjectBackupProgressService>()
        val before = withConnectionManager {
            val payload = BackupObject("content", "asset", "revision", 4, "a".repeat(64), "payloads/asset")
            val pending = progress.prepare(ObjectBackupOperation.BACKUP, "source", "retained", "content", payload)
            val running = progress.start(pending.key, pending.version)
            progress.complete(running.key, running.version)
        }
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select to_regclass('backup.object_capture_plans')").use {
                    assertTrue(it.next())
                    assertNotNull(it.getString(1))
                }
                statement.executeQuery("select count(*) from backup.object_capture_plans").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
            }
        }
        assertEquals(before, withConnectionManager { progress.get(before.key) })
    }
}
