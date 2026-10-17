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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verifies that address binding upgrades preserve existing unbound run and progress records. */
@OptIn(InternalDI::class)
class ObjectBackupStorageMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_storage_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password),
            key = "object-backup-storage-upgrade-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true) { application }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = listOf(
                "V1__backup_object_progress.sql", "V2__backup_object_runs.sql",
                "V3__object_backup_operations.sql", "V4__object_backup_payload_claims.sql",
            )
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
    fun `upgrade preserves legacy runs and checkpoints without inventing storage locations`(): Unit = runBlocking {
        val service = provide<ObjectBackupRunService>()
        val inventory = ObjectBackupManifest(
            "legacy-inventory", "legacy-source", "content", "2026-10-16T00:00:00Z",
            listOf(BackupObject("content", "asset", "revision-1", 4,
                "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7", "objects/asset")),
        )
        val run = withConnectionManager { service.prepare(inventory, "legacy-destination") }
        val items = withConnectionManager { service.items(run.key, 0, 10) }
        val migration = provide<Migration>(name = "backup-migrations")

        FlywayMigration(pool).migrate(listOf(migration))
        FlywayMigration(pool).migrate(listOf(migration))

        assertEquals(run, withConnectionManager { service.get(run.key) })
        assertEquals(items, withConnectionManager { service.items(run.key, 0, 10) })
        assertNull(withConnectionManager { service.getStorage(run.key) })
        val storage = provide<ObjectBackupStorageService>()
        assertNull(withConnectionManager { storage.get("legacy-destination") })
    }
}
