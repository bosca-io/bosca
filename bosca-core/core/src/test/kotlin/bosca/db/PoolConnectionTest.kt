@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package bosca.db

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Savepoint
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PoolConnectionTest {

    private fun jdbc(): Connection = mockk(relaxed = true) {
        every { isClosed } returns false
        every { autoCommit } returns true
        every { isReadOnly } returns false
        every { transactionIsolation } returns Connection.TRANSACTION_READ_COMMITTED
        every { isValid(any()) } returns true
    }

    @Test
    fun `state validation and request lifecycle enforce ordering`() {
        val jdbc = jdbc()
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        connection.validateState(ConnectionState.RELEASED)
        assertFailsWith<IllegalArgumentException> { connection.validateState(ConnectionState.OBTAINED) }
        assertFailsWith<IllegalStateException> { connection.beginRequest() }

        connection.state = ConnectionState.OBTAINED
        connection.beginRequest()
        verify { jdbc.beginRequest() }
        assertFailsWith<IllegalStateException> { connection.endRequest() }

        connection.state = ConnectionState.RELEASED
        connection.endRequest()
        verify { jdbc.endRequest() }
    }

    @Test
    fun `connection properties active flag and health delegate to JDBC`() {
        val jdbc = jdbc()
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        assertFalse(connection.isClosed)
        assertTrue(connection.autoCommit)
        assertFalse(connection.readOnly)
        connection.autoCommit = false
        connection.readOnly = true
        verify { jdbc.autoCommit = false }
        verify { jdbc.isReadOnly = true }
        assertTrue(connection.markActive())
        assertFalse(connection.markActive())
        assertTrue(connection.markInactive())
        assertFalse(connection.markInactive())
        assertTrue(connection.validate())
        verify { jdbc.isValid(2) }
    }

    @Test
    fun `transaction operations require obtained state and delegate every overload`() {
        val jdbc = jdbc()
        val unnamed = mockk<Savepoint>()
        val named = mockk<Savepoint>()
        val array = mockk<java.sql.Array>()
        every { jdbc.setSavepoint() } returns unnamed
        every { jdbc.setSavepoint("named") } returns named
        every { jdbc.createArrayOf("text", any()) } returns array
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)

        assertFailsWith<IllegalStateException> { connection.setSavepoint() }
        assertFailsWith<IllegalStateException> { connection.rollback() }
        assertFailsWith<IllegalStateException> { connection.commit() }
        assertFailsWith<IllegalStateException> { connection.createArrayOf("text", arrayOf("x")) }

        connection.state = ConnectionState.OBTAINED
        assertSame(unnamed, connection.setSavepoint())
        assertSame(named, connection.setSavepoint("named"))
        connection.rollback()
        connection.rollback(named)
        connection.commit()
        assertSame(array, connection.createArrayOf("text", arrayOf("x")))
        verify { jdbc.rollback() }
        verify { jdbc.rollback(named) }
        verify { jdbc.commit() }
    }

    @Test
    fun `statements require obtained state and delegate`() {
        val jdbc = jdbc()
        val prepared = mockk<PreparedStatement>()
        val statement = mockk<Statement>()
        every { jdbc.prepareStatement("select 1") } returns prepared
        every { jdbc.createStatement() } returns statement
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        assertFailsWith<IllegalStateException> { connection.prepareStatement("select 1") }
        assertFailsWith<IllegalStateException> { connection.createStatement() }

        connection.state = ConnectionState.OBTAINED
        assertSame(prepared, connection.prepareStatement("select 1"))
        assertSame(statement, connection.createStatement())
    }

    @Test
    fun `reset rolls back and restores all mutable JDBC state`() {
        val jdbc = jdbc()
        every { jdbc.autoCommit } returns false
        every { jdbc.isReadOnly } returns true
        every { jdbc.transactionIsolation } returns Connection.TRANSACTION_SERIALIZABLE
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)

        connection.reset()

        verify { jdbc.rollback() }
        verify { jdbc.autoCommit = true }
        verify { jdbc.isReadOnly = false }
        verify { jdbc.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED }
        verify { jdbc.clearWarnings() }
    }

    @Test
    fun `reset rejects obtained and closed connections and skips clean state changes`() {
        val jdbc = jdbc()
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        connection.state = ConnectionState.OBTAINED
        assertFailsWith<IllegalStateException> { connection.reset() }

        connection.state = ConnectionState.RELEASED
        every { jdbc.isClosed } returns true
        assertFailsWith<IllegalStateException> { connection.reset() }

        every { jdbc.isClosed } returns false
        connection.reset()
        verify(exactly = 0) { jdbc.rollback() }
        verify(exactly = 0) { jdbc.autoCommit = any() }
        verify(exactly = 0) { jdbc.isReadOnly = any() }
        verify(exactly = 0) { jdbc.transactionIsolation = any() }
        verify { jdbc.clearWarnings() }
    }

    @Test
    fun `close requires released state`() {
        val jdbc = jdbc()
        val connection = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        connection.state = ConnectionState.OBTAINED
        assertFailsWith<IllegalStateException> { connection.close() }
        connection.state = ConnectionState.RELEASED
        connection.close()
        verify { jdbc.close() }
    }

    @Test
    fun `identity equality and hash code distinguish pooled wrappers`() {
        val jdbc = jdbc()
        val first = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        val second = PoolConnection(jdbc, Connection.TRANSACTION_READ_COMMITTED)
        assertEquals(first, first)
        assertFalse(first.equals(null))
        assertFalse(first.equals("connection"))
        assertNotEquals(first, second)
        assertEquals(first.id.hashCode(), first.hashCode())
    }
}
