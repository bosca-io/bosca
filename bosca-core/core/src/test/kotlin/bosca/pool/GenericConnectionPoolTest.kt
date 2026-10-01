package bosca.pool

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GenericConnectionPoolTest {

    private data class Connection(val id: Int, var alive: Boolean = true, var valid: Boolean = true)

    private class Factory(override val maxConnections: Int) : PoolableConnectionFactory<Connection> {
        var nextId = 0
        var createFailure: Throwable? = null
        var validateFailure: Throwable? = null
        var destroyFailure: Throwable? = null
        val created = mutableListOf<Connection>()
        val destroyed = mutableListOf<Connection>()

        override suspend fun create(): Connection {
            createFailure?.let { throw it }
            return Connection(++nextId).also(created::add)
        }

        override suspend fun validate(connection: Connection): Boolean {
            validateFailure?.let { throw it }
            return connection.valid
        }

        override suspend fun destroy(connection: Connection) {
            destroyed += connection
            destroyFailure?.let { throw it }
        }

        override fun isAlive(connection: Connection): Boolean = connection.alive
    }

    @Test
    fun `pool creates reuses waits and exposes counts`() = runBlocking {
        val factory = Factory(maxConnections = 1)
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000, acquireTimeoutMs = 1_000)
        try {
            assertEquals(1, pool.maxConnections)
            assertEquals(0, pool.createdConnections)
            assertEquals(0, pool.activeConnections)

            val first = pool.obtain()
            assertEquals(1, pool.createdConnections)
            assertEquals(1, pool.activeConnections)

            val waiting = async { pool.obtain() }
            delay(20)
            assertTrue(!waiting.isCompleted)
            pool.release(first)
            assertSame(first, waiting.await())
            assertEquals(1, factory.created.size)
            pool.release(first)
        } finally {
            pool.close()
        }
        assertEquals(0, pool.createdConnections)
        assertEquals(0, pool.activeConnections)
        assertEquals(factory.created, factory.destroyed)
    }

    @Test
    fun `create failures and acquire timeout leave accurate counts`() = runBlocking {
        val failure = IllegalStateException("create failed")
        val factory = Factory(maxConnections = 1).apply { createFailure = failure }
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000, acquireTimeoutMs = 25)
        try {
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { pool.obtain() }.message)
            assertEquals(0, pool.createdConnections)
            factory.createFailure = null
            val held = pool.obtain()
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { pool.obtain() }
            pool.release(held)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `dead idle connection is discarded and replaced`() = runBlocking {
        val factory = Factory(maxConnections = 1)
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000)
        try {
            val dead = pool.obtain()
            pool.release(dead)
            dead.alive = false

            val replacement = pool.obtain()
            assertTrue(replacement !== dead)
            assertEquals(2, factory.created.size)
            assertEquals(listOf(dead), factory.destroyed)
            assertEquals(1, pool.createdConnections)
            pool.release(replacement)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `discarding an active dead connection supplies a replacement to a waiter`() = runBlocking {
        val factory = Factory(maxConnections = 1)
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000, acquireTimeoutMs = 1_000)
        try {
            val held = pool.obtain()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { pool.obtain() }
            assertFalse(waiting.isCompleted)

            held.alive = false
            pool.release(held)
            val replacement = waiting.await()

            assertTrue(replacement !== held)
            assertEquals(listOf(held), factory.destroyed)
            assertEquals(2, factory.created.size)
            pool.release(replacement)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `release discards connections that die while active`() = runBlocking {
        val factory = Factory(maxConnections = 1)
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000)
        try {
            val connection = pool.obtain()
            connection.alive = false
            pool.release(connection)
            assertEquals(0, pool.activeConnections)
            assertEquals(0, pool.createdConnections)
            assertEquals(listOf(connection), factory.destroyed)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `monitor retains healthy idle connections`() = runBlocking {
        val factory = Factory(maxConnections = 1)
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 5, validateTimeoutMs = 100)
        try {
            val connection = pool.obtain()
            pool.release(connection)
            delay(30)
            assertSame(connection, pool.obtain())
            pool.release(connection)
            assertTrue(factory.destroyed.isEmpty())
        } finally {
            pool.close()
        }
    }

    @Test
    fun `monitor discards invalid dead and throwing idle connections`() = runBlocking {
        suspend fun exercise(configure: (Factory, Connection) -> Unit) {
            val factory = Factory(maxConnections = 1)
            val pool = GenericConnectionPool(factory, monitorIntervalMs = 5, validateTimeoutMs = 50)
            try {
                val connection = pool.obtain()
                pool.release(connection)
                configure(factory, connection)
                withTimeout(1_000) {
                    while (pool.createdConnections != 0) delay(5)
                }
                assertEquals(listOf(connection), factory.destroyed)
            } finally {
                pool.close()
            }
        }

        exercise { _, connection -> connection.valid = false }
        exercise { _, connection -> connection.alive = false }
        exercise { factory, _ -> factory.validateFailure = IllegalStateException("validation failed") }
    }

    @Test
    fun `close tolerates destroy failures`() = runBlocking {
        val factory = Factory(maxConnections = 1).apply {
            destroyFailure = IllegalStateException("destroy failed")
        }
        val pool = GenericConnectionPool(factory, monitorIntervalMs = 60_000)
        val connection = pool.obtain()
        pool.release(connection)

        pool.close()

        assertEquals(listOf(connection), factory.destroyed)
        assertEquals(0, pool.createdConnections)
    }
}
