package bosca.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.nats.client.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NatsConnectionPoolFailureTest {

    @Test
    fun `cancelling a caller does not cancel physical creation shared by another caller`() = runBlocking {
        val physical = connection()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val creations = AtomicInteger()
        val pool = NatsConnectionPool(maxConnections = 1) {
            creations.incrementAndGet()
            entered.complete(Unit)
            release.await()
            physical
        }
        try {
            withTimeout(5_000) {
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { pool.openConnection("document") }
                entered.await()
                val surviving = async(start = CoroutineStart.UNDISPATCHED) { pool.openConnection("document") }
                cancelled.cancelAndJoin()
                release.complete(Unit)
                val logical = surviving.await()
                try {
                    logical.publish("document", byteArrayOf(1))
                } finally {
                    logical.close()
                }
            }
            assertEquals(1, creations.get())
            verify(exactly = 1) { physical.publish("document", byteArrayOf(1)) }
        } finally {
            release.complete(Unit)
            pool.close()
        }
        verify(exactly = 1) { physical.close() }
    }

    @Test
    fun `concurrent callers share one physical creation and retain independent logical lifetimes`() = runBlocking {
        val physical = connection()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val creations = AtomicInteger()
        val pool = NatsConnectionPool(maxConnections = 1) {
            creations.incrementAndGet()
            entered.complete(Unit)
            release.await()
            physical
        }
        try {
            withTimeout(5_000) {
                val first = async(start = CoroutineStart.UNDISPATCHED) { pool.openConnection() }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { pool.openConnection() }
                release.complete(Unit)
                val a = first.await()
                val b = second.await()
                assertEquals(2, pool.activeLogicalConnectionCount)
                a.close()
                assertEquals(1, pool.activeLogicalConnectionCount)
                b.publish("remaining", byteArrayOf(2))
                b.close()
                assertEquals(0, pool.activeLogicalConnectionCount)
            }
            assertEquals(1, creations.get())
            verify(exactly = 1) { physical.publish("remaining", byteArrayOf(2)) }
            verify(exactly = 0) { physical.close() }
        } finally {
            release.complete(Unit)
            pool.close()
        }
        verify(exactly = 1) { physical.close() }
    }

    @Test
    fun `failed creation is not retained and a later request can establish the connection`() = runBlocking {
        val physical = connection()
        val failure = IOException("temporary connection failure")
        val attempts = AtomicInteger()
        val pool = NatsConnectionPool(maxConnections = 1) {
            if (attempts.getAndIncrement() == 0) throw failure
            physical
        }
        try {
            assertEquals(failure.message, assertFailsWith<IOException> { pool.systemConnection() }.message)
            assertSame(physical, pool.systemConnection())
            assertSame(physical, pool.systemConnection())
            assertEquals(2, attempts.get())
        } finally {
            pool.close()
        }
        verify(exactly = 1) { physical.close() }
    }

    @Test
    fun `connection already closed on arrival is released and retried before returning a handle`() = runBlocking {
        val closed = connection(Connection.Status.CLOSED)
        val replacement = connection()
        val attempts = AtomicInteger()
        val pool = NatsConnectionPool(maxConnections = 1) {
            if (attempts.getAndIncrement() == 0) closed else replacement
        }
        try {
            withTimeout(5_000) {
                val logical = pool.openConnection()
                try {
                    logical.publish("retry", byteArrayOf(3))
                } finally {
                    logical.close()
                }
            }
            assertEquals(2, attempts.get())
            verify(exactly = 1) { closed.close() }
            verify(exactly = 0) { closed.publish(any(), any<ByteArray>()) }
            verify(exactly = 1) { replacement.publish("retry", byteArrayOf(3)) }
        } finally {
            pool.close()
        }
        verify(exactly = 1) { closed.close() }
        verify(exactly = 1) { replacement.close() }
    }

    @Test
    fun `listener registration failure releases the new connection and allows retry`() = runBlocking {
        val rejected = connection()
        val replacement = connection()
        val failure = IllegalStateException("listener registration failed")
        every { rejected.addConnectionListener(any()) } throws failure
        val attempts = AtomicInteger()
        val pool = NatsConnectionPool(maxConnections = 1) {
            if (attempts.getAndIncrement() == 0) rejected else replacement
        }
        try {
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { pool.systemConnection() }.message)
            assertSame(replacement, pool.systemConnection())
            verify(exactly = 1) { rejected.close() }
            assertEquals(0, pool.activeLogicalConnectionCount)
        } finally {
            pool.close()
        }
        verify(exactly = 1) { replacement.close() }
    }

    @Test
    fun `closing the pool cancels pending creation and rejects every subsequent opening`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pool = NatsConnectionPool(maxConnections = 1) {
            entered.complete(Unit)
            try {
                CompletableDeferred<Connection>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        withTimeout(5_000) {
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<kotlinx.coroutines.CancellationException> { pool.openConnection() }
            }
            entered.await()
            pool.close()
            opening.await()
            cancelled.await()
        }
        assertEquals(0, pool.activeLogicalConnectionCount)
        assertTrue(assertFailsWith<IllegalStateException> { pool.openConnection() }.message.orEmpty().contains("closed"))
        assertFailsWith<IllegalStateException> { pool.systemConnection() }
        pool.close()
    }

    private fun connection(status: Connection.Status = Connection.Status.CONNECTED): Connection = mockk<Connection>().also {
        every { it.status } returns status
        every { it.addConnectionListener(any()) } just runs
        every { it.removeConnectionListener(any()) } just runs
        every { it.publish(any(), any<ByteArray>()) } just runs
        every { it.close() } just runs
    }
}
