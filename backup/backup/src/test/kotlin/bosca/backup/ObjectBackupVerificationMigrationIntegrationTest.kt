package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.test.resources.SharedPostgreSQLContainer
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Upgrades real pre-verification progress without inventing integrity evidence. */
class ObjectBackupVerificationMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private val proofColumns = setOf("verified_info", "verified")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_verification_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password),
            key = "object-backup-verification-upgrade-test",
        ))
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V9__") }
        }))
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `verification migration is registered by the owning module`() {
        assertTrue("V9__object_backup_verification.sql" in BackupMigration().resources)
    }

    @Test
    fun `upgrade preserves completed progress without inventing verified provider versions`(): Unit = runBlocking {
        val payload = BackupObject("content", "asset", "source-v1", 4, "a".repeat(64), "payloads/legacy")
        connection { connection ->
            connection.prepareStatement("""
                insert into backup.object_paths (destination_id, path, length, sha256)
                values ('retained', ?, 4, ?)
            """.trimIndent()).use {
                it.setString(1, payload.payloadPath)
                it.setString(2, payload.sha256)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into backup.object_progress
                    (key, source_id, destination_id, scope, operation, payload, status, attempts, verified_bytes, version)
                values (?, 'source', 'retained', 'content', 'backup', ?::jsonb, 'completed', 1, 4, 2)
            """.trimIndent()).use {
                it.setString(1, "a".repeat(64))
                it.setString(2, Json.encodeToString(BackupObject.serializer(), payload))
                assertEquals(1, it.executeUpdate())
            }
        }
        val before = row()
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        val count = connection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema = 'backup' and table_name = 'object_progress'
                        and column_name in ('verified_info', 'verified')
                """.trimIndent()).use { result ->
                    assertTrue(result.next())
                    result.getInt(1)
                }
            }
        }
        assertEquals(2, count)
        val after = row()
        assertEquals(before, after.filterKeys { it !in proofColumns })
        assertEquals(proofColumns, after.filterKeys { it in proofColumns }.keys)
        assertTrue(after.filterKeys { it in proofColumns }.values.all { it == JsonNull })
    }

    private fun row() = connection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select to_jsonb(p)::text from backup.object_progress p").use {
                assertTrue(it.next())
                Json.parseToJsonElement(it.getString(1)).jsonObject
            }
        }
    }

    private fun <T> connection(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)
}
