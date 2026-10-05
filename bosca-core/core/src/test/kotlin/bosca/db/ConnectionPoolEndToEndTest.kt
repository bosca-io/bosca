package bosca.db

import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests verifying the [ConnectionPool] and [ConnectionManager] against a real
 * PostgreSQL instance managed by TestContainers.
 *
 * These tests exercise JDBC connection lifecycle, transaction management, savepoint-based
 * nested transactions, and concurrent connection pooling with actual database round-trips.
 */
class ConnectionPoolEndToEndTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool

    @BeforeTest
    fun setup() = runBlocking {
        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgresContainer.start()

        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
                maxConnections = 10
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)

        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
    }

    private suspend fun <T> withManager(block: suspend (ConnectionManager) -> T): T {
        val cm = ConnectionManager(connectionPool)
        return try {
            withContext(cm.asCoroutineContext()) {
                block(cm)
            }
        } finally {
            withContext(NonCancellable) {
                cm.release()
            }
        }
    }

    @Test
    fun `obtainConnection returns working connection that can execute queries`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.useStatement("SELECT 1 AS result") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("result"))
            }
        }
    }

    @Test
    fun `connection pool reuses released connections`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // Obtain and release a connection, then verify pool state
        withManager { cm ->
            cm.useStatement("SELECT 1") { it.executeQuery() }
        }
        assertEquals(0, connectionPool.activeConnections)
        assertTrue(connectionPool.createdConnections >= 1)

        // Second use should reuse the released connection
        val createdBefore = connectionPool.createdConnections
        withManager { cm ->
            cm.useStatement("SELECT 1") { it.executeQuery() }
        }
        assertEquals(createdBefore, connectionPool.createdConnections, "Should reuse existing connection")
    }

    @Test
    fun `transaction commits data to PostgreSQL`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("CREATE TABLE IF NOT EXISTS e2e_commit_test (id serial PRIMARY KEY, value text)") { it.execute() }
            cm.useStatement("INSERT INTO e2e_commit_test (value) VALUES ('committed')") { it.execute() }
            cm.commitTransaction()
        }

        // Verify committed data persists across a new connection
        withManager { cm ->
            cm.useStatement("SELECT value FROM e2e_commit_test WHERE value = 'committed'") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Committed row should be visible in a new connection")
                assertEquals("committed", rs.getString("value"))
            }
        }
    }

    @Test
    fun `transaction rollback discards changes`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // Create table first
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("CREATE TABLE IF NOT EXISTS e2e_rollback_test (id serial PRIMARY KEY, value text)") { it.execute() }
            cm.commitTransaction()
        }

        // Insert and rollback
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("INSERT INTO e2e_rollback_test (value) VALUES ('rolled_back')") { it.execute() }
            cm.rollbackTransaction()
        }

        // Verify rolled-back data is not visible
        withManager { cm ->
            cm.useStatement("SELECT COUNT(*) FROM e2e_rollback_test WHERE value = 'rolled_back'") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1), "Rolled-back row should not exist")
            }
        }
    }

    @Test
    fun `nested transactions via savepoints partially rollback`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("CREATE TABLE IF NOT EXISTS e2e_savepoint_test (id serial PRIMARY KEY, value text)") { it.execute() }
            cm.commitTransaction()
        }

        withManager { cm ->
            cm.beginTransaction()

            // Insert first row (outer transaction)
            cm.useStatement("INSERT INTO e2e_savepoint_test (value) VALUES ('outer')") { it.execute() }

            // Start nested transaction (savepoint)
            cm.beginTransaction()
            cm.useStatement("INSERT INTO e2e_savepoint_test (value) VALUES ('inner')") { it.execute() }

            // Rollback inner savepoint only
            cm.rollbackTransaction()

            // Commit outer transaction
            cm.commitTransaction()
        }

        // Verify only the outer row persisted
        withManager { cm ->
            cm.useStatement("SELECT value FROM e2e_savepoint_test ORDER BY id") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("outer", rs.getString("value"))
                assertFalse(rs.next(), "Inner savepoint row should have been rolled back")
            }
        }
    }

    @Test
    fun `commit callback fires after successful commit`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        var commitCalled = false
        var releaseCalled = false

        withManager { cm ->
            cm.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() { commitCalled = true }
                override suspend fun onRelease() { releaseCalled = true }
            })

            cm.beginTransaction()
            cm.useStatement("SELECT 1") { it.executeQuery() }
            cm.commitTransaction()

            assertTrue(commitCalled, "onCommit callback should fire after commit")
        }

        assertTrue(releaseCalled, "onRelease callback should fire after release")
    }

    @Test
    fun `rollback callback fires after rollback`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        var rollbackCalled = false

        withManager { cm ->
            cm.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() {}
                override suspend fun onRollback() { rollbackCalled = true }
                override suspend fun onRelease() {}
            })

            cm.beginTransaction()
            cm.useStatement("SELECT 1") { it.executeQuery() }
            cm.rollbackTransaction()

            assertTrue(rollbackCalled, "onRollback callback should fire after rollback")
        }
    }

    @Test
    fun `commit callbacks fire only once with nested transactions and commitAll`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        var commitCount = 0

        withManager { cm ->
            cm.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() { commitCount++ }
                override suspend fun onRelease() {}
            })

            // Outer transaction
            cm.beginTransaction()
            cm.useStatement("SELECT 1") { it.executeQuery() }

            // Nested transaction (savepoint)
            cm.beginTransaction()
            cm.useStatement("SELECT 1") { it.executeQuery() }

            // commitTransaction(all = true) should commit through savepoints and fire callbacks once
            cm.commitTransaction(all = true)
            assertEquals(1, commitCount, "onCommit should fire exactly once after commitTransaction(all = true)")

            // Subsequent commits should NOT re-fire callbacks
            cm.beginTransaction()
            cm.useStatement("SELECT 1") { it.executeQuery() }
            cm.commitTransaction()
            assertEquals(1, commitCount, "onCommit registered in a prior transaction should not re-fire in a new transaction")
        }
    }

    @Test
    fun `rollback does not suppress subsequent commit callbacks`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        var commitCount = 0
        var rollbackCount = 0

        withManager { cm ->
            // First transaction: rollback — drains transaction callbacks
            cm.beginTransaction()
            cm.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() { commitCount++ }
                override suspend fun onRollback() { rollbackCount++ }
                override suspend fun onRelease() {}
            })
            cm.rollbackTransaction()
            assertEquals(1, rollbackCount, "onRollback should fire")
            assertEquals(0, commitCount, "onCommit should not fire on rollback")

            // Second transaction: commit with a fresh callback
            cm.beginTransaction()
            cm.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() { commitCount++ }
                override suspend fun onRollback() { rollbackCount++ }
                override suspend fun onRelease() {}
            })
            cm.commitTransaction()
            assertEquals(1, commitCount, "onCommit should fire on second transaction's commit")
            assertEquals(1, rollbackCount, "onRollback should not fire again on commit")
        }
    }

    @Test
    fun `concurrent connections execute independently`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("CREATE TABLE IF NOT EXISTS e2e_concurrent_test (id serial PRIMARY KEY, thread_name text)") { it.execute() }
            cm.commitTransaction()
        }

        val results = (1..5).map { i ->
            async {
                withManager { cm ->
                    cm.beginTransaction()
                    cm.useStatement("INSERT INTO e2e_concurrent_test (thread_name) VALUES ('worker-$i')") { it.execute() }
                    cm.commitTransaction()
                }
                i
            }
        }.awaitAll()

        assertEquals(5, results.size)

        withManager { cm ->
            cm.useStatement("SELECT COUNT(*) FROM e2e_concurrent_test") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(5, rs.getInt(1), "All 5 concurrent inserts should be committed")
            }
        }
    }

    @Test
    fun `useReadOnlyStatement sets connection to read-only`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.useReadOnlyStatement("SELECT 1") { stmt ->
                assertTrue(stmt.connection.isReadOnly, "Connection should be read-only")
                stmt.executeQuery()
            }
        }
    }

    @Test
    fun `pool tracks active and created connections accurately`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        assertEquals(0, connectionPool.activeConnections)

        val cm1 = ConnectionManager(connectionPool)
        val cm2 = ConnectionManager(connectionPool)

        // Force connection acquisition
        withContext(cm1.asCoroutineContext()) {
            cm1.beginTransaction()
        }
        withContext(cm2.asCoroutineContext()) {
            cm2.beginTransaction()
        }

        assertEquals(2, connectionPool.activeConnections)
        assertTrue(connectionPool.createdConnections >= 2)

        withContext(NonCancellable) {
            cm1.commitTransaction()
            cm1.release()
            cm2.commitTransaction()
            cm2.release()
        }

        assertEquals(0, connectionPool.activeConnections)
    }

    @Test
    fun `Flyway migrations create expected schema tables`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            // Verify core tables exist by querying pg_tables
            val expectedTables = listOf("collections", "metadata", "principals", "groups", "profiles")
            for (table in expectedTables) {
                cm.useStatement("SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = ?") { stmt ->
                    stmt.setString(1, table)
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next(), "Table '$table' should exist after core migration")
                }
            }
        }
    }

    @Test
    fun `transaction function commits on success and rolls back on exception`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        withManager { cm ->
            cm.beginTransaction()
            cm.useStatement("CREATE TABLE IF NOT EXISTS e2e_txn_fn_test (id serial PRIMARY KEY, value text)") { it.execute() }
            cm.commitTransaction()
        }

        // Successful transaction
        withManager { cm ->
            withContext(cm.asCoroutineContext()) {
                transaction {
                    connection().useStatement("INSERT INTO e2e_txn_fn_test (value) VALUES ('success')") { it.execute() }
                }
            }
        }

        // Failed transaction
        try {
            withManager { cm ->
                withContext(cm.asCoroutineContext()) {
                    transaction {
                        connection().useStatement("INSERT INTO e2e_txn_fn_test (value) VALUES ('fail')") { it.execute() }
                        error("Intentional failure")
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // Expected
        }

        // Verify only the successful row exists
        withManager { cm ->
            cm.useStatement("SELECT value FROM e2e_txn_fn_test ORDER BY id") { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("success", rs.getString("value"))
                assertFalse(rs.next(), "Failed transaction row should not exist")
            }
        }
    }
}
