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
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Existing native requests retain their intent and ownership when configured content capture is added. */
@OptIn(InternalDI::class)
class PostgresBackupContentSourceMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("postgres_content_source_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), "postgres-content-source-upgrade",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V20__") }
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
    fun `upgrade preserves database only intent running tokens and destination claims`(): Unit = runBlocking {
        jdbc { sql ->
            sql.executeUpdate("insert into backup.object_stores (key, type, base_path) values ('retained', 'fs', '/retained')")
            sql.executeUpdate("insert into backup.postgres_runs (key, source_id, storage_key, prefix) values ('pending', 'source', 'retained', 'backups')")
            sql.executeUpdate("""
                insert into backup.postgres_runs (key, source_id, storage_key, prefix, status, writer_token, attempts, version)
                values ('running', 'source', 'retained', 'backups', 'running', 'job:attempt', 2, 4)
            """.trimIndent())
            sql.executeUpdate("insert into backup.object_store_writers (storage_key, postgres_key) values ('retained', 'running')")
        }
        val before = snapshots("select (to_jsonb(r) - 'content_source')::text from backup.postgres_runs r order by key")
        val owners = snapshots("select to_jsonb(w)::text from backup.object_store_writers w")
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        assertEquals(before, snapshots("select (to_jsonb(r) - 'content_source')::text from backup.postgres_runs r order by key"))
        assertEquals(owners, snapshots("select to_jsonb(w)::text from backup.object_store_writers w"))
        withConnectionManager {
            val repository = provide<PostgresBackupRunRepository>()
            assertEquals(null, assertNotNull(repository.get("pending")).contentSource)
            assertEquals(null, assertNotNull(repository.get("running")).contentSource)
            val source = PostgresBackupContentSource("content", "configured-source", "snapshot")
            assertEquals(1, repository.add("content", "source", "retained", "backups", true, source))
            assertEquals(source, assertNotNull(repository.get("content")).contentSource)
        }
        assertFailsWith<java.sql.SQLException> {
            jdbc { it.executeUpdate("update backup.postgres_runs set content_source='[]'::jsonb where key='content'") }
        }
        assertFailsWith<java.sql.SQLException> {
            jdbc { it.executeUpdate("update backup.postgres_runs set content_source='{}'::jsonb where key='pending'") }
        }
        val resource = "db/migrations/V20__postgres_backup_content_source.sql"
        assertNotNull(javaClass.classLoader.getResource(resource))
        val metadata = assertNotNull(javaClass.classLoader.getResourceAsStream(
            "META-INF/native-image/io.bosca/backup/reachability-metadata.json",
        )).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertTrue(metadata.getValue("resources").jsonArray.any { it.jsonObject["glob"]?.toString() == "\"$resource\"" })
    }

    private fun snapshots(query: String) = jdbc { sql ->
        sql.executeQuery(query).use { rows -> buildList {
            while (rows.next()) add(Json.parseToJsonElement(rows.getString(1)))
        } }
    }

    private fun <T> jdbc(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use(block)
        }
}
