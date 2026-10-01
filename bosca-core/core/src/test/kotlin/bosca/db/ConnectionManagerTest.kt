@file:OptIn(ExperimentalAtomicApi::class)

package bosca.db

import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Savepoint
import java.sql.Statement
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.withContext

class ConnectionManagerTest {

    private lateinit var factory: ConnectionFactory
    private lateinit var pool: ConnectionPool
    private lateinit var connectionManager: ConnectionManager
    private lateinit var rawConnection: Connection
    private lateinit var statement: Statement
    private lateinit var preparedStatement: PreparedStatement
    private var currentAutoCommit = true
    private var currentReadOnly = false

    @BeforeTest
    fun setUp() {
        factory = mockk(relaxed = true)
        rawConnection = mockk(relaxed = true)
        statement = mockk(relaxed = true)
        preparedStatement = mockk(relaxed = true)
        
        every { factory.key } returns "test"
        every { factory.maxConnections } returns 1
        every { factory.create() } returns rawConnection
        
        // Mock raw connection behavior with stateful autoCommit
        currentAutoCommit = true
        every { rawConnection.autoCommit } answers { currentAutoCommit }
        every { rawConnection.autoCommit = any() } answers { currentAutoCommit = firstArg() }
        
        every { rawConnection.createStatement() } returns statement
        every { rawConnection.prepareStatement(any()) } returns preparedStatement
        every { rawConnection.isValid(any()) } returns true
        every { rawConnection.isClosed } returns false
        
        every { rawConnection.commit() } just Runs
        every { rawConnection.rollback() } just Runs
        every { rawConnection.clearWarnings() } just Runs
        every { rawConnection.transactionIsolation } returns Connection.TRANSACTION_READ_COMMITTED
        every { rawConnection.transactionIsolation = any() } just Runs
        currentReadOnly = false
        every { rawConnection.isReadOnly } answers { currentReadOnly }
        every { rawConnection.isReadOnly = any() } answers { currentReadOnly = firstArg() }

        // Use REAL ConnectionPool
        pool = ConnectionPool(factory)
        
        connectionManager = ConnectionManager(pool)
    }
    
    @AfterTest
    fun tearDown() = runBlocking {
        pool.close()
    }

    @Test
    fun `beginTransaction sets autoCommit to false`() = runBlocking {
        // Setup initial state
        currentAutoCommit = true
        
        connectionManager.beginTransaction()
        
        // Verification on the RAW connection
        verify { rawConnection.autoCommit = false }
        // Verify explicit BEGIN is NOT executed
        verify(exactly = 0) { statement.execute("BEGIN;") }
    }
    
    @Test
    fun `commitTransaction commits and resets autoCommit`() = runBlocking {
        currentAutoCommit = true
        
        connectionManager.beginTransaction()
        connectionManager.commitTransaction()
        
        verify { rawConnection.commit() }
        verify { rawConnection.autoCommit = true }
    }

    @Test
    fun `rollbackTransaction rollbacks and resets autoCommit`() = runBlocking {
        currentAutoCommit = true
        
        connectionManager.beginTransaction()
        connectionManager.rollbackTransaction()
        
        verify { rawConnection.rollback() }
        verify { rawConnection.autoCommit = true }
    }

    @Test
    fun `nested transactions use savepoints and outer commit invokes callbacks`() = runBlocking {
        val savepoint = mockk<Savepoint>()
        every { rawConnection.setSavepoint("SAVEPOINT_0") } returns savepoint
        val callback = mockk<ConnectionManagerCallback>(relaxed = true)
        val failing = mockk<ConnectionManagerCallback>(relaxed = true)
        coEvery { failing.onCommit() } throws IllegalStateException("callback failure")
        connectionManager.addCallback(failing)
        connectionManager.addCallback(callback)

        connectionManager.beginTransaction()
        connectionManager.beginTransaction()
        assertTrue(connectionManager.inTransaction)
        assertTrue(connectionManager.needsCommitOrRollback)
        connectionManager.commitTransaction()
        verify(exactly = 0) { rawConnection.commit() }
        connectionManager.commitTransaction()

        verify { rawConnection.commit() }
        coVerify { failing.onCommit() }
        coVerify { callback.onCommit() }
        assertFalse(connectionManager.inTransaction)
        assertFalse(connectionManager.needsCommitOrRollback)
        connectionManager.release()
        coVerify { failing.onRelease() }
        coVerify { callback.onRelease() }
    }

    @Test
    fun `nested and outer rollback invoke savepoint and rollback callbacks`() = runBlocking {
        val savepoint = mockk<Savepoint>()
        every { rawConnection.setSavepoint("SAVEPOINT_0") } returns savepoint
        val callback = mockk<ConnectionManagerCallback>(relaxed = true)
        connectionManager.addCallback(callback)
        connectionManager.beginTransaction()
        connectionManager.beginTransaction()

        connectionManager.rollbackTransaction()
        verify { rawConnection.rollback(savepoint) }
        assertTrue(connectionManager.inTransaction)
        connectionManager.rollbackTransaction()
        verify { rawConnection.rollback() }
        coVerify { callback.onRollback() }
        connectionManager.release()
        coVerify { callback.onRelease() }
    }

    @Test
    fun `statement helpers return values set read-only and auto-release`() = runBlocking {
        val result = connectionManager.useStatement("select value") {
            delay(1)
            "result"
        }
        assertEquals("result", result)
        verify { rawConnection.prepareStatement("select value") }
        verify { preparedStatement.close() }

        val readOnlyResult = connectionManager.useReadOnlyStatement("select readonly") {
            delay(1)
            7
        }
        assertEquals(7, readOnlyResult)
        verify { rawConnection.isReadOnly = true }
        verify { rawConnection.isReadOnly = false }
    }

    @Test
    fun `release during transaction rolls back and is idempotent`() = runBlocking {
        val callback = mockk<ConnectionManagerCallback>(relaxed = true)
        connectionManager.addCallback(callback)
        connectionManager.beginTransaction()
        connectionManager.release()
        connectionManager.release()

        verify(exactly = 1) { rawConnection.rollback() }
        coVerify(exactly = 1) { callback.onRollback() }
        coVerify(exactly = 1) { callback.onRelease() }
        assertFailsWith<IllegalStateException> { connectionManager.useStatement("select 1") { } }
        Unit
    }

    @Test
    fun `context helpers transactions and afterCommit handle immediate deferred and failure paths`() = runBlocking {
        assertFailsWith<IllegalStateException> { connection() }
        assertNull(connectionOrNull())
        var immediate = false
        afterCommit { immediate = true }
        assertTrue(immediate)

        withContext(connectionManager.asCoroutineContext()) {
            assertSame(connectionManager, connection())
            assertSame(connectionManager, connectionOrNull())
            var contextualImmediate = false
            afterCommit {
                delay(1)
                contextualImmediate = true
            }
            assertTrue(contextualImmediate)
            var deferred = false
            connectionManager.beginTransaction()
            afterCommit {
                delay(1)
                deferred = true
            }
            assertFalse(deferred)
            connectionManager.commitTransaction()
            assertTrue(deferred)

            assertEquals("committed", transaction { "committed" })
            assertFailsWith<IllegalStateException> {
                transaction { throw IllegalStateException("rollback") }
            }
        }
        connectionManager.release()
    }

    @Test
    fun `use extension releases manager on success and failure`() = runBlocking {
        val success = ConnectionManager(pool)
        assertEquals("value", success.use { "value" })
        assertFailsWith<IllegalStateException> { success.useStatement("select 1") { } }

        val failure = ConnectionManager(pool)
        assertFailsWith<IllegalArgumentException> {
            failure.use { throw IllegalArgumentException("failure") }
        }
        assertFailsWith<IllegalStateException> { failure.useStatement("select 1") { } }
        Unit
    }

    @Test
    fun `transaction state early exits array delegation and forced outer commit`() = runBlocking {
        connectionManager.markNeedsCommitOrRollback()
        assertFalse(connectionManager.needsCommitOrRollback)
        connectionManager.commitTransaction()
        connectionManager.rollbackTransaction()

        val sqlArray = mockk<java.sql.Array>()
        every { rawConnection.createArrayOf("uuid", any()) } returns sqlArray
        assertSame(sqlArray, connectionManager.createArrayOf("uuid", arrayOf("one")))

        currentAutoCommit = false
        connectionManager.beginTransaction()
        verify(exactly = 0) { rawConnection.autoCommit = false }
        connectionManager.markNeedsCommitOrRollback()
        assertTrue(connectionManager.needsCommitOrRollback)

        val savepoint = mockk<Savepoint>()
        every { rawConnection.setSavepoint("SAVEPOINT_0") } returns savepoint
        connectionManager.beginTransaction()
        connectionManager.commitTransaction(all = true)

        verify { rawConnection.commit() }
        assertFalse(connectionManager.inTransaction)
        assertFalse(connectionManager.needsCommitOrRollback)
    }

    @Test
    fun `release without a connection invokes callbacks once`() = runBlocking {
        val callback = object : ConnectionManagerCallback {
            var commits = 0
            var releases = 0
            override suspend fun onCommit() { commits++ }
            override suspend fun onRelease() { releases++ }
        }
        connectionManager.addCallback(callback)

        connectionManager.release()
        connectionManager.release()

        assertEquals(0, callback.commits)
        assertEquals(1, callback.releases)
    }

    @Test
    fun `statement failure still releases and read only transaction retains connection`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            connectionManager.useStatement("broken") { throw IllegalArgumentException("broken") }
        }
        assertEquals(0, pool.activeConnections)

        connectionManager.beginTransaction()
        assertEquals("held", connectionManager.useReadOnlyStatement("select held") { "held" })
        assertEquals(1, pool.activeConnections)
        connectionManager.rollbackTransaction()
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `rollback tolerates a transaction already returned to auto commit`() = runBlocking {
        connectionManager.beginTransaction()
        currentAutoCommit = true

        connectionManager.rollbackTransaction()

        verify(exactly = 0) { rawConnection.rollback() }
        assertFalse(connectionManager.inTransaction)
        assertFalse(connectionManager.needsCommitOrRollback)
    }

    @Test
    fun `concurrent first use shares the connection established under the manager mutex`() = runBlocking {
        val first = async { connectionManager.createArrayOf("uuid", arrayOf("first")) }
        val second = async { connectionManager.createArrayOf("uuid", arrayOf("second")) }

        first.await()
        second.await()

        verify(exactly = 1) { factory.create() }
        verify(exactly = 2) { rawConnection.createArrayOf("uuid", any()) }
        connectionManager.release()
    }
}
