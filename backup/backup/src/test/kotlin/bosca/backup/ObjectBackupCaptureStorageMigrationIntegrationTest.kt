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
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
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

/** Upgrades saved selections without inventing storage locations or logical mappings. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureStorageMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture_storage_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), key = "capture-storage-upgrade-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V11__") }
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
    fun `capture storage migration is registered`() {
        assertTrue("V11__object_backup_capture_storage.sql" in BackupMigration().resources)
    }

    @Test
    fun `upgrade preserves saved plans exactly without inventing configured storage`(): Unit = runBlocking {
        val service = provide<ObjectBackupCaptureService>()
        val inventory = ObjectBackupCaptureInventory("saved", "source", "content", "2026-10-18T00:00:00Z",
            mapOf("content" to listOf(ObjectStorageEntry("asset", ObjectStorageInfo(4, version = "v1")))))
        val before = withConnectionManager { service.prepareInventory(inventory, "retained", "payloads", "manifests/saved.json") }
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                for (table in listOf("object_capture_storage", "object_capture_stores")) {
                    statement.executeQuery("select to_regclass('backup.$table')").use {
                        assertTrue(it.next())
                        assertNotNull(it.getString(1))
                    }
                    statement.executeQuery("select count(*) from backup.$table").use {
                        assertTrue(it.next())
                        assertEquals(0, it.getInt(1))
                    }
                }
            }
        }
        assertEquals(before, withConnectionManager { service.getPlan(before.key) })
    }
}
