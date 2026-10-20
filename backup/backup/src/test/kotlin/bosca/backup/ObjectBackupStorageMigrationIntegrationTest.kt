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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val runKey = seedLegacyInventory(inventory)
        val before = snapshot()
        val migration = provide<Migration>(name = "backup-migrations")

        FlywayMigration(pool).migrate(listOf(migration))
        FlywayMigration(pool).migrate(listOf(migration))

        assertEquals(before, snapshot())
        val run = withConnectionManager { assertNotNull(service.get(runKey)) }
        assertEquals(inventory, run.inventory)
        assertEquals(inventory.objects, withConnectionManager { service.items(runKey, 0, 10) }.map { it.payload })
        assertNull(withConnectionManager { service.getStorage(runKey) })
        val storage = provide<ObjectBackupStorageService>()
        assertNull(withConnectionManager { storage.get("legacy-destination") })
    }

    private fun seedLegacyInventory(inventory: ObjectBackupManifest): String {
        val destinationId = "legacy-destination"
        val payload = inventory.objects.single()
        val runKey = objectBackupIdentity(
            ObjectBackupOperation.BACKUP, listOf(inventory.sourceId, destinationId, inventory.scope, inventory.id),
        )
        val itemKey = objectBackupIdentity(
            ObjectBackupOperation.BACKUP,
            listOf(inventory.sourceId, destinationId, inventory.scope, payload.store, payload.key, payload.revision),
        )
        val path = "manifests/$runKey.json"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("""
                insert into backup.object_paths (destination_id, path, length, sha256) values (?, ?, ?, ?)
            """.trimIndent()).use {
                it.setString(1, destinationId)
                it.setString(2, payload.payloadPath)
                it.setLong(3, payload.length)
                it.setString(4, payload.sha256)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_paths (destination_id, path, manifest_key) values (?, ?, ?)
            """.trimIndent()).use {
                it.setString(1, destinationId)
                it.setString(2, path)
                it.setString(3, runKey)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_progress (key, source_id, destination_id, scope, operation, payload)
                values (?, ?, ?, ?, 'backup', ?::jsonb)
            """.trimIndent()).use {
                it.setString(1, itemKey)
                it.setString(2, inventory.sourceId)
                it.setString(3, destinationId)
                it.setString(4, inventory.scope)
                it.setString(5, Json.encodeToString(BackupObject.serializer(), payload))
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_runs (key, source_id, destination_id, scope, operation, inventory, path)
                values (?, ?, ?, ?, 'backup', ?::jsonb, ?)
            """.trimIndent()).use {
                it.setString(1, runKey)
                it.setString(2, inventory.sourceId)
                it.setString(3, destinationId)
                it.setString(4, inventory.scope)
                it.setString(5, Json.encodeToString(ObjectBackupManifest.serializer(), inventory))
                it.setString(6, path)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_run_items (run_key, position, progress_key) values (?, 0, ?)
            """.trimIndent()).use {
                it.setString(1, runKey)
                it.setString(2, itemKey)
                assertEquals(1, it.executeUpdate())
            }
            connection.commit()
        }
        return runKey
    }

    private fun snapshot() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                select jsonb_build_object(
                    'runs', (select jsonb_agg(to_jsonb(r) order by key) from backup.object_runs r),
                    'progress', (select jsonb_agg(to_jsonb(p) - 'verified_version' - 'verified_info' - 'verified' order by key)
                        from backup.object_progress p),
                    'items', (select jsonb_agg(to_jsonb(i) order by run_key, position) from backup.object_run_items i)
                )::text
            """.trimIndent()).use {
                assertTrue(it.next())
                Json.parseToJsonElement(it.getString(1))
            }
        }
    }
}
