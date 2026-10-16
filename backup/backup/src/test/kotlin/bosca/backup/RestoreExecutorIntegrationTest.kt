package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.di.annotation.InternalDI
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises archive restore and transaction rollback against an isolated real PostgreSQL database. */
@OptIn(InternalDI::class)
class RestoreExecutorIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var fixture: BackupExecutorFixture

    @BeforeTest
    fun setUp() {
        postgres = SharedPostgreSQLContainer().withDatabaseName("backup_restore").apply { start() }
        val pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 1),
            key = "restore-integration-test",
        ))
        fixture = BackupExecutorFixture(pool)
        sql("CREATE TABLE traits (id varchar PRIMARY KEY, name varchar NOT NULL, description varchar NOT NULL)")
    }

    @AfterTest
    fun tearDown() = runBlocking {
        try {
            if (::fixture.isInitialized) fixture.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `skip keeps existing records while importing new records`() = runBlocking {
        sql("INSERT INTO traits VALUES ('existing', 'Original', 'Description')")
        fixture.restore(data = mapOf("traits" to rows("existing", "new")))

        assertEquals(listOf("existing:Original", "new:Imported"), records())
    }

    @Test
    fun `overwrite updates existing primary key conflicts`() = runBlocking {
        sql("INSERT INTO traits VALUES ('existing', 'Original', 'Description')")
        fixture.restore(data = mapOf("traits" to rows("existing")), conflictStrategy = ConflictStrategy.OVERWRITE)

        assertEquals(listOf("existing:Imported"), records())
    }

    @Test
    fun `fail strategy rolls back on primary key conflicts`() = runBlocking {
        sql("INSERT INTO traits VALUES ('existing', 'Original', 'Description')")
        val error = assertFailsWith<SQLException> {
            fixture.restore(data = mapOf("traits" to rows("new", "existing")), conflictStrategy = ConflictStrategy.FAIL)
        }

        assertEquals("23505", error.sqlState)
        assertEquals(listOf("existing:Original"), records())
    }

    @Test
    fun `skip propagates a constraint failure and rolls back earlier inserts`() = runBlocking {
        verifyConstraintRollback(ConflictStrategy.SKIP)
    }

    @Test
    fun `overwrite propagates a constraint failure and rolls back earlier inserts`() = runBlocking {
        verifyConstraintRollback(ConflictStrategy.OVERWRITE)
    }

    @Test
    fun `declared row mismatch leaves existing records and files untouched`() = runBlocking {
        sql("INSERT INTO traits VALUES ('existing', 'Original', 'Description')")
        assertFailsWith<IllegalArgumentException> {
            fixture.restore(
                data = mapOf("traits" to rows("new")),
                files = mapOf("metadata/item/1/content" to "data".toByteArray()),
                manifest = BackupManifest(createdAt = "2026-10-15T00:00:00Z", counts = mutableMapOf("traits" to 2L)),
            )
        }

        assertEquals(listOf("existing:Original"), records())
        assertTrue(fixture.writtenFiles.isEmpty())
    }

    private suspend fun verifyConstraintRollback(strategy: ConflictStrategy) {
        val data = rows("new") + "\n{\"id\":\"invalid\",\"name\":null,\"description\":\"Description\"}"
        val error = assertFailsWith<SQLException> {
            fixture.restore(
                data = mapOf("traits" to data),
                files = mapOf("metadata/item/1/content" to "data".toByteArray()),
                conflictStrategy = strategy,
            )
        }

        assertEquals("23502", error.sqlState)
        assertEquals(emptyList(), records())
        assertTrue(fixture.writtenFiles.isEmpty())
    }

    private fun rows(vararg ids: String) = ids.joinToString("\n") {
        "{\"id\":\"$it\",\"name\":\"Imported\",\"description\":\"Description\"}"
    }

    private fun sql(query: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(query) }
        }
    }

    private fun records(): List<String> = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, name FROM traits ORDER BY id").use { rows ->
                buildList { while (rows.next()) add("${rows.getString(1)}:${rows.getString(2)}") }
            }
        }
    }
}
