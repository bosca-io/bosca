package bosca.db

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotSame
import kotlin.test.expect

class ConnectionPoolTest {

    private lateinit var factory: ConnectionFactory
    private lateinit var pool: ConnectionPool
    private val maxConnections = 5

    @BeforeTest
    fun setUp() {
        factory = mockk(relaxed = true)
        every { factory.maxConnections } returns maxConnections
        every { factory.key } returns "test-pool"
        
        // Default behavior: create valid connections
        every { factory.create() } answers {
            mockk<Connection>(relaxed = true) {
                every { isValid(any()) } returns true
                every { isClosed } returns false
                every { autoCommit } returns true
            }
        }
        
        pool = ConnectionPool(factory)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        pool.close()
    }

    @Test
    fun `obtainConnection creates new connection when pool is empty`() = runBlocking {
        val conn = pool.obtainConnection()
        assertNotNull(conn)
        verify(exactly = 1) { factory.create() }
        assertEquals(1, pool.createdConnections)
        assertEquals(1, pool.activeConnections)
    }

    @Test
    fun `obtainConnection reuses released connection`() = runBlocking {
        val conn1 = pool.obtainConnection()
        verify(exactly = 1) { factory.create() }

        pool.releaseConnection(conn1)
        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)
        assertTrue(pool.hasAvailableConnections)

        val conn2 = pool.obtainConnection()
        assertEquals(conn1, conn2)
        verify(exactly = 1) { factory.create() } // Should not create new one
        assertEquals(1, pool.activeConnections)
    }

    @Test
    fun `obtain and release participate in the contextual connection limit`() = runBlocking {
        val limiter = ConnectionLimiter(limit = 1)

        withContext(limiter.asCoroutineContext()) {
            val connection = pool.obtainConnection()
            pool.releaseConnection(connection)
        }

        withContext(limiter.asCoroutineContext()) {
            val reused = pool.obtainConnection()
            pool.releaseConnection(reused)
        }
        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)
    }

    @Test
    fun `failed obtain returns its contextual limiter permit`() = runBlocking {
        val limiter = ConnectionLimiter(limit = 1)
        every { factory.create() } throws SQLException("connect failed")

        withContext(limiter.asCoroutineContext()) {
            assertFailsWith<SQLException> { pool.obtainConnection() }
        }

        every { factory.create() } returns mockk<Connection>(relaxed = true) {
            every { isValid(any()) } returns true
            every { isClosed } returns false
            every { autoCommit } returns true
        }
        withContext(limiter.asCoroutineContext()) {
            val connection = withTimeout(1_000) { pool.obtainConnection() }
            pool.releaseConnection(connection)
        }
    }

    @Test
    fun `pool respects maxConnections and suspends`() = runBlocking {
        val connections = (1..maxConnections).map { pool.obtainConnection() }
        assertEquals(maxConnections, pool.activeConnections)
        assertEquals(maxConnections, pool.createdConnections)

        var nextConnection: PoolConnection? = null
        val job = launch {
            nextConnection = pool.obtainConnection()
        }

        // Allow job to start and hit suspension
        delay(200)
        assertNull(nextConnection)

        // Release one
        pool.releaseConnection(connections[0])

        // Job should complete now
        job.join()

        assertNotNull(nextConnection)
        assertEquals(connections[0], nextConnection)
    }

    @Test
    fun `obtainConnection discards and retries if beginRequest fails`() = runTest {
        val badConnection = mockk<Connection>(relaxed = true) {
            every { beginRequest() } throws SQLException("Bad connection")
            every { isValid(any()) } returns false
            every { isClosed } returns false
            every { autoCommit } returns true
        }

        val goodConnection = mockk<Connection>(relaxed = true) {
            every { isValid(any()) } returns true
            every { isClosed } returns false
            every { autoCommit } returns true
        }

        every { factory.create() } returnsMany listOf(badConnection, goodConnection)

        val conn = pool.obtainConnection()

        verify(exactly = 2) { factory.create() }
        verify { badConnection.close() }
        assertEquals(1, pool.activeConnections)
        assertEquals(true, conn.validate())
    }

    @Test
    fun `releaseConnection discards connection if closed`() = runBlocking {
        val conn = pool.obtainConnection()
        every { conn.isClosed } returns true

        pool.releaseConnection(conn)

        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)
        verify(exactly = 0) { conn.endRequest() } // Should not call endRequest on closed connection
    }

    @Test
    fun `releaseConnection counts`() = runBlocking {
        val conn = pool.connection()
        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        conn.release()

        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        val conn2 = pool.connection()
        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        conn2.useStatement("select 1") {
            assertEquals(1, pool.activeConnections)
            assertEquals(1, pool.createdConnections)
        }

        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)

        conn2.release()

        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)
    }

    @Test
    fun `releaseConnection counts in transaction`() = runBlocking {
        val conn = pool.connection()
        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        conn.release()

        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        val conn2 = pool.connection()
        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)

        conn2.beginTransaction()

        assertEquals(1, pool.activeConnections)
        assertEquals(1, pool.createdConnections)

        conn2.useStatement("select 1") {
            assertEquals(1, pool.activeConnections)
            assertEquals(1, pool.createdConnections)
        }

        assertEquals(1, pool.activeConnections)
        assertEquals(1, pool.createdConnections)

        conn2.commitTransaction()

        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)

        conn2.release()

        assertEquals(0, pool.activeConnections)
        assertEquals(1, pool.createdConnections)
    }

    @Test
    fun `releaseConnection discards connection if reset fails`() = runBlocking {
        val conn = pool.obtainConnection()
        // Simulate failure during reset (e.g. autoCommit check or set)
        every { conn.autoCommit } throws SQLException("Reset failed")

        pool.releaseConnection(conn)

        assertEquals(0, pool.activeConnections)
        assertEquals(0, pool.createdConnections)
        verify { conn.close() }
    }

    @Test
    fun `obtain discards a connection that died while idle and replaces it at capacity`() = runBlocking {
        var firstClosed = false
        val firstRaw = mockk<Connection>(relaxed = true) {
            every { isClosed } answers { firstClosed }
            every { isValid(any()) } returns true
            every { autoCommit } returns true
        }
        val secondRaw = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { isValid(any()) } returns true
            every { autoCommit } returns true
        }
        val localFactory = mockk<ConnectionFactory>()
        every { localFactory.key } returns "idle-death"
        every { localFactory.maxConnections } returns 1
        every { localFactory.create() } returnsMany listOf(firstRaw, secondRaw)
        val localPool = ConnectionPool(localFactory)
        try {
            val first = localPool.obtainConnection()
            localPool.releaseConnection(first)
            firstClosed = true

            val replacement = withTimeout(1_000) { localPool.obtainConnection() }
            assertNotSame(first, replacement)
            assertEquals(1, localPool.createdConnections)
            verify(exactly = 2) { localFactory.create() }
            localPool.releaseConnection(replacement)
        } finally {
            localPool.close()
        }
    }

    @Test
    fun `idle validation retains healthy connections`() = runBlocking {
        val connection = pool.obtainConnection()
        pool.releaseConnection(connection)

        pool.validateIdleConnections()

        assertEquals(1, pool.createdConnections)
        assertTrue(pool.hasAvailableConnections)
        verify(exactly = 0) { connection.close() }
    }

    @Test
    fun `idle validation discards closed invalid and failing connections`() = runBlocking {
        suspend fun validateAndAssertDiscarded(raw: Connection, mutate: () -> Unit) {
            val localFactory = mockk<ConnectionFactory> {
                every { key } returns "idle-health"
                every { maxConnections } returns 1
                every { create() } returns raw
            }
            val localPool = ConnectionPool(localFactory)
            try {
                val connection = localPool.obtainConnection()
                localPool.releaseConnection(connection)
                mutate()
                localPool.validateIdleConnections()
                assertEquals(0, localPool.createdConnections)
                assertFalse(localPool.hasAvailableConnections)
                verify { raw.close() }
            } finally {
                localPool.close()
            }
        }

        var closed = false
        val closedRaw = mockk<Connection>(relaxed = true) {
            every { isClosed } answers { closed }
            every { isValid(any()) } returns true
            every { autoCommit } returns true
        }
        validateAndAssertDiscarded(closedRaw) { closed = true }

        var valid = true
        val invalidRaw = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { isValid(any()) } answers { valid }
            every { autoCommit } returns true
        }
        validateAndAssertDiscarded(invalidRaw) { valid = false }

        var fail = false
        val failingRaw = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { isValid(any()) } answers { if (fail) throw SQLException("validation failed") else true }
            every { autoCommit } returns true
        }
        validateAndAssertDiscarded(failingRaw) { fail = true }
    }

    @Test
    fun `connection creation failure restores pool capacity`() = runBlocking {
        every { factory.create() } throws SQLException("connect failed")

        assertFailsWith<SQLException> { pool.obtainConnection() }

        assertEquals(0, pool.createdConnections)
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `second begin request failure is propagated after one retry`() = runBlocking {
        val first = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { autoCommit } returns true
            every { beginRequest() } throws SQLException("first")
        }
        val second = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { autoCommit } returns true
            every { beginRequest() } throws SQLException("second")
        }
        every { factory.create() } returnsMany listOf(first, second)

        assertEquals("second", assertFailsWith<SQLException> { pool.obtainConnection() }.message)

        verify { first.close() }
        verify { second.close() }
        assertEquals(0, pool.createdConnections)
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `newly created closed connection is discarded immediately`() = runBlocking {
        val closed = mockk<Connection>(relaxed = true) {
            every { isClosed } returns true
            every { autoCommit } returns true
        }
        every { factory.create() } returns closed

        assertFailsWith<IllegalStateException> { pool.obtainConnection() }

        verify { closed.close() }
        assertEquals(0, pool.createdConnections)
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `discard while a caller waits creates and hands off a replacement`() = runBlocking {
        var failReset = false
        val first = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { isValid(any()) } returns true
            every { autoCommit } answers { if (failReset) throw SQLException("reset") else true }
        }
        val replacement = mockk<Connection>(relaxed = true) {
            every { isClosed } returns false
            every { isValid(any()) } returns true
            every { autoCommit } returns true
        }
        val localFactory = mockk<ConnectionFactory> {
            every { key } returns "waiting-replacement"
            every { maxConnections } returns 1
            every { create() } returnsMany listOf(first, replacement)
        }
        val localPool = ConnectionPool(localFactory)
        try {
            val obtained = localPool.obtainConnection()
            val waiting = async { localPool.obtainConnection() }
            delay(50)
            failReset = true

            localPool.releaseConnection(obtained)
            val handedOff = withTimeout(1_000) { waiting.await() }

            assertNotSame(obtained, handedOff)
            verify(exactly = 2) { localFactory.create() }
            localPool.releaseConnection(handedOff)
        } finally {
            localPool.close()
        }
    }
}
