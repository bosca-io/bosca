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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adding job checkpoints preserves older runs without assigning them to an arbitrary job. */
@OptIn(InternalDI::class)
class ObjectBackupJobCheckpointMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("job_checkpoint_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), key = "job-checkpoint-upgrade",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V17__") }
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
    fun `upgrade preserves old attempts and claims without inventing job checkpoints`(): Unit = runBlocking {
        val repository = provide<ObjectBackupRunRepository>()
        withConnectionManager {
            for (key in listOf("pending", "running")) {
                val manifest = ObjectBackupManifest(key, "source", "content", "2026-10-20T00:00:00Z", emptyList())
                val run = ObjectBackupRun(key, "source", "retained", "content", ObjectBackupOperation.BACKUP,
                    manifest, "manifests/$key.json")
                provide<ObjectBackupProgressService>().reserveManifest(run.destinationId, run.path, run.key)
                assertEquals(1, repository.add(run))
            }
        }
        jdbc { sql ->
            sql.executeUpdate("update backup.object_runs set status = 'running', attempts = 1, version = 1 where key = 'running'")
            sql.executeUpdate("insert into backup.object_stores (key, type, base_path) values ('retained', 'fs', '/retained')")
            sql.executeUpdate("insert into backup.object_store_writers (storage_key, run_key) values ('retained', 'running')")
        }
        val before = snapshots()
        val owners = snapshot("select to_jsonb(w)::text from backup.object_store_writers w")

        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))

        assertEquals(before, snapshots())
        assertEquals(owners, snapshot("select to_jsonb(w)::text from backup.object_store_writers w"))
        withConnectionManager {
            assertNull(assertNotNull(repository.get("running")).checkpointKey)
            assertNull(assertNotNull(repository.get("pending")).checkpointKey)
            assertNull(repository.resume("running", 1, "unrelated-job"))
            val started = assertNotNull(repository.start("pending", 0, "new-job-checkpoint"))
            assertEquals("new-job-checkpoint", started.checkpointKey)
            assertEquals(started, repository.get(started.key))
        }
        val resource = "db/migrations/V17__object_backup_job_checkpoints.sql"
        assertNotNull(javaClass.classLoader.getResource(resource))
        val metadata = assertNotNull(javaClass.classLoader.getResourceAsStream(
            "META-INF/native-image/io.bosca/backup/reachability-metadata.json",
        )).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertTrue(metadata.getValue("resources").jsonArray.any { it.jsonObject["glob"]?.toString() == "\"$resource\"" })
    }

    private fun snapshots() = jdbc { sql ->
        sql.executeQuery("select (to_jsonb(r) - 'checkpoint_key')::text from backup.object_runs r order by key").use { rows ->
            buildList { while (rows.next()) add(Json.parseToJsonElement(rows.getString(1))) }
        }
    }

    private fun snapshot(query: String) = jdbc { sql ->
        sql.executeQuery(query).use { rows ->
            assertTrue(rows.next())
            Json.parseToJsonElement(rows.getString(1))
        }
    }

    private fun <T> jdbc(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use(block)
        }
}
