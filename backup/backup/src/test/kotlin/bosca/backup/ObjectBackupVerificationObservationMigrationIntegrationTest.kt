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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Converts existing verification records while leaving unverified historical bytes unverified. */
class ObjectBackupVerificationObservationMigrationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("verification_observation_upgrade").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password),
            key = "verification-observation-upgrade"))
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "backup"
            override val resources = BackupMigration().resources.takeWhile { !it.startsWith("V16__") }
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
    fun `observation migration is registered in its owning provider`() {
        assertTrue("V16__object_backup_verification_observations.sql" in BackupMigration().resources)
    }

    @Test
    fun `upgrade preserves a real provider verification and does not invent unversioned evidence`(): Unit = runBlocking {
        seed("verified", "provider-v1")
        seed("unverified", null)
        val original = row("verified")
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        val verified = row("verified")
        assertFalse("verified_version" in verified)
        val info = verified.getValue("verified_info").jsonObject
        assertEquals(4, info.getValue("length").jsonPrimitive.long)
        assertEquals("provider-v1", info.getValue("version").jsonPrimitive.content)
        assertEquals(original.getValue("verified"), verified.getValue("verified"))
        assertEquals(original.filterKeys { it != "verified_version" }, verified.filterKeys { it != "verified_info" })
        val unverified = row("unverified")
        assertEquals(JsonNull, unverified.getValue("verified_info"))
        assertEquals(JsonNull, unverified.getValue("verified"))
    }

    private fun seed(key: String, version: String?) {
        val payload = BackupObject("content", "asset-$key", "source", 4, "a".repeat(64), "payloads/$key")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
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
                    (key, source_id, destination_id, scope, operation, payload, status, attempts,
                        verified_bytes, version, verified_version, verified)
                values (?, 'source', 'retained', 'content', 'backup', ?::jsonb, 'completed', 1, 4, 2, ?,
                    case when cast(? as text) is null then null else '2026-10-20T00:00:00Z'::timestamptz end)
            """.trimIndent()).use {
                it.setString(1, key)
                it.setString(2, Json.encodeToString(BackupObject.serializer(), payload))
                it.setString(3, version)
                it.setString(4, version)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun row(key: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
        connection.prepareStatement("select to_jsonb(p)::text from backup.object_progress p where key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { row ->
                assertTrue(row.next())
                Json.parseToJsonElement(row.getString(1)).jsonObject
            }
        }
    }
}
