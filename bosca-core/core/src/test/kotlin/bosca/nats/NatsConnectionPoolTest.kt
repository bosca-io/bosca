package bosca.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Dispatcher
import io.nats.client.MessageHandler
import io.nats.client.Subscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NatsConnectionPoolTest {

    @Test
    fun `logical connections are distributed round robin across shared physical connections`() = runTest {
        val system = mockk<Connection>()
        val first = mockk<Connection>()
        val second = mockk<Connection>()
        every { first.publish(any(), any<ByteArray>()) } just runs
        every { second.publish(any(), any<ByteArray>()) } just runs
        val manager = NatsConnectionPool(system, listOf(first, second))

        assertSame(system, manager.systemConnection())
        assertEquals(2, manager.maxConnections)

        repeat(5) { index ->
            val connection = manager.openConnection()
            try {
                connection.publish("subject", byteArrayOf(index.toByte()))
            } finally {
                connection.close()
            }
        }

        verifyOrder {
            first.publish("subject", byteArrayOf(0))
            second.publish("subject", byteArrayOf(1))
            first.publish("subject", byteArrayOf(2))
            second.publish("subject", byteArrayOf(3))
            first.publish("subject", byteArrayOf(4))
        }
        verify(exactly = 0) { system.publish(any(), any<ByteArray>()) }
    }

    @Test
    fun `manager close closes owned physical connections once and rejects new handles`() = runTest {
        val system = mockk<Connection>()
        val first = mockk<Connection>()
        val second = mockk<Connection>()
        every { system.close() } just runs
        every { first.close() } just runs
        every { second.close() } just runs
        every { first.status } returns Connection.Status.CONNECTED
        val manager = NatsConnectionPool(system, listOf(first, second), ownsConnections = true)
        val logicalConnection = manager.openConnection()

        manager.close()
        manager.close()

        assertFailsWith<IllegalStateException> { logicalConnection.publish("subject", byteArrayOf()) }
        assertFailsWith<IllegalStateException> { manager.openConnection() }
        assertFailsWith<IllegalStateException> { manager.openConnection("subject") }
        assertFailsWith<IllegalStateException> { manager.systemConnection() }
        verify(exactly = 1) { system.close() }
        verify(exactly = 1) { first.close() }
        verify(exactly = 1) { second.close() }
    }

    @Test
    fun `partitioned logical connections retain physical connection affinity`() = runTest {
        val system = mockk<Connection>()
        val first = mockk<Connection>()
        val second = mockk<Connection>()
        every { first.publish(any(), any<ByteArray>()) } just runs
        every { second.publish(any(), any<ByteArray>()) } just runs
        val manager = NatsConnectionPool(system, listOf(first, second))
        val subject = "ordered.subject"
        val expected = listOf(first, second)[Math.floorMod(subject.hashCode(), 2)]

        repeat(3) { index ->
            val connection = manager.openConnection(subject)
            try {
                connection.publish(subject, byteArrayOf(index.toByte()))
            } finally {
                connection.close()
            }
        }

        verify(exactly = 1) { expected.publish(subject, byteArrayOf(0)) }
        verify(exactly = 1) { expected.publish(subject, byteArrayOf(1)) }
        verify(exactly = 1) { expected.publish(subject, byteArrayOf(2)) }
    }

    @Test
    fun `injected single connection is not physically closed by manager`() = runTest {
        val physicalConnection = mockk<Connection>()
        val manager = NatsConnectionPool(physicalConnection)

        assertSame(physicalConnection, manager.systemConnection())
        manager.close()

        verify(exactly = 0) { physicalConnection.close() }
    }

    @Test
    fun `closed physical connection is replaced for new logical handles`() = runTest {
        val first = mockk<Connection>()
        val replacement = mockk<Connection>()
        var firstStatus = Connection.Status.CONNECTED
        every { first.status } answers { firstStatus }
        every { first.publish(any(), any<ByteArray>()) } just runs
        every { replacement.publish(any(), any<ByteArray>()) } just runs
        every { replacement.status } returns Connection.Status.CONNECTED
        every { first.addConnectionListener(any()) } just runs
        every { replacement.addConnectionListener(any()) } just runs
        every { first.removeConnectionListener(any()) } just runs
        every { replacement.removeConnectionListener(any()) } just runs
        every { first.close() } just runs
        every { replacement.close() } just runs
        val physicalConnections = ArrayDeque(listOf(first, replacement))
        val manager = NatsConnectionPool(maxConnections = 1) {
            physicalConnections.removeFirst()
        }

        val firstLogicalConnection = manager.openConnection()
        try {
            firstLogicalConnection.publish("subject", byteArrayOf(1))
        } finally {
            firstLogicalConnection.close()
        }
        firstStatus = Connection.Status.CLOSED
        val replacementLogicalConnection = manager.openConnection()
        try {
            replacementLogicalConnection.publish("subject", byteArrayOf(2))
        } finally {
            replacementLogicalConnection.close()
        }
        manager.close()

        verify(exactly = 1) { first.publish("subject", byteArrayOf(1)) }
        verify(exactly = 1) { replacement.publish("subject", byteArrayOf(2)) }
        verify(exactly = 1) { replacement.close() }
    }

    @Test
    fun `closed system connection is replaced`() = runTest {
        val first = mockk<Connection>()
        val replacement = mockk<Connection>()
        var firstStatus = Connection.Status.CONNECTED
        every { first.status } answers { firstStatus }
        every { replacement.status } returns Connection.Status.CONNECTED
        every { first.addConnectionListener(any()) } just runs
        every { replacement.addConnectionListener(any()) } just runs
        every { first.removeConnectionListener(any()) } just runs
        every { replacement.removeConnectionListener(any()) } just runs
        every { first.close() } just runs
        every { replacement.close() } just runs
        val physicalConnections = ArrayDeque(listOf(first, replacement))
        val manager = NatsConnectionPool(maxConnections = 1) {
            physicalConnections.removeFirst()
        }

        assertSame(first, manager.systemConnection())
        firstStatus = Connection.Status.CLOSED
        assertSame(replacement, manager.systemConnection())
        manager.close()

        verify(exactly = 1) { replacement.close() }
    }

    @Test
    fun `default authentication constructors retain default pool size`() = runTest {
        val tokenManager = NatsConnectionPool("nats://127.0.0.1:4222", "token")
        val userManager = NatsConnectionPool("nats://127.0.0.1:4222", "user", "password")

        assertEquals(NatsConnectionPool.DEFAULT_MAX_CONNECTIONS, tokenManager.maxConnections)
        assertEquals(NatsConnectionPool.DEFAULT_MAX_CONNECTIONS, userManager.maxConnections)
        tokenManager.close()
        userManager.close()
    }

    @Test
    fun `supplied closed physical connection cannot be replaced without a factory`() = runTest {
        val system = mockk<Connection>()
        val closedConnection = mockk<Connection>()
        every { system.close() } just runs
        every { closedConnection.close() } just runs
        every { closedConnection.status } returns Connection.Status.CLOSED
        val manager = NatsConnectionPool(system, listOf(closedConnection), ownsConnections = true)

        assertFailsWith<IllegalStateException> { manager.openConnection() }
        manager.close()
    }

    @Test
    fun `manager requires at least one pooled connection`() {
        assertFailsWith<IllegalArgumentException> {
            NatsConnectionPool(mockk(), emptyList())
        }
        assertEquals(50, NatsConnectionPool.DEFAULT_MAX_CONNECTIONS)
    }

    @Test
    fun `independent physical slots connect concurrently`() = runTest {
        val connections = List(2) {
            mockk<Connection>().also { connection ->
                every { connection.status } returns Connection.Status.CONNECTED
                every { connection.addConnectionListener(any()) } just runs
                every { connection.removeConnectionListener(any()) } just runs
                every { connection.close() } just runs
            }
        }
        val nextConnection = AtomicInteger()
        val entered = Channel<Unit>(capacity = 2)
        val release = CompletableDeferred<Unit>()
        val manager = NatsConnectionPool(maxConnections = 2) {
            val connection = connections[nextConnection.getAndIncrement()]
            entered.send(Unit)
            release.await()
            connection
        }

        val first = async { manager.openConnection() }
        val second = async { manager.openConnection() }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                entered.receive()
                entered.receive()
            }
        }
        release.complete(Unit)

        first.await().close()
        second.await().close()
        manager.close()
        assertEquals(2, nextConnection.get())
    }

    @Test
    fun `client slot creation does not block an initialized system connection`() = runTest {
        val system = mockk<Connection>()
        val client = mockk<Connection>()
        listOf(system, client).forEach { connection ->
            every { connection.status } returns Connection.Status.CONNECTED
            every { connection.addConnectionListener(any()) } just runs
            every { connection.removeConnectionListener(any()) } just runs
            every { connection.close() } just runs
        }
        val invocation = AtomicInteger()
        val clientEntered = CompletableDeferred<Unit>()
        val releaseClient = CompletableDeferred<Unit>()
        val manager = NatsConnectionPool(maxConnections = 1) {
            if (invocation.getAndIncrement() == 0) {
                system
            } else {
                clientEntered.complete(Unit)
                releaseClient.await()
                client
            }
        }

        assertSame(system, manager.systemConnection())
        val logical = async(Dispatchers.Default) { manager.openConnection() }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { clientEntered.await() }
        }
        withTimeout(1_000) { assertSame(system, manager.systemConnection()) }
        releaseClient.complete(Unit)

        logical.await().close()
        manager.close()
    }

    @Test
    fun `connection creation has a bounded timeout`() = runTest {
        val manager = NatsConnectionPool(maxConnections = 1, connectionCreationTimeoutMs = 25) {
            awaitCancellation()
        }

        assertFailsWith<IllegalStateException> { manager.openConnection() }
        manager.close()
    }

    @Test
    fun `terminal physical close fails old flows and permits replacement`() = runTest {
        val first = mockk<Connection>()
        val replacement = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        val messageHandler = slot<MessageHandler>()
        val connectionListener = slot<ConnectionListener>()
        var firstStatus = Connection.Status.CONNECTED
        every { first.status } answers { firstStatus }
        every { replacement.status } returns Connection.Status.CONNECTED
        every { first.addConnectionListener(capture(connectionListener)) } just runs
        every { replacement.addConnectionListener(any()) } just runs
        every { first.removeConnectionListener(any()) } just runs
        every { replacement.removeConnectionListener(any()) } just runs
        every { first.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe("subject", capture(messageHandler)) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { first.closeDispatcher(dispatcher) } just runs
        every { replacement.publish(any(), any<ByteArray>()) } just runs
        every { first.close() } just runs
        every { replacement.close() } just runs
        val invocation = AtomicInteger()
        val manager = NatsConnectionPool(maxConnections = 1) {
            if (invocation.getAndIncrement() == 0) first else replacement
        }

        val oldLogical = manager.openConnection()
        val oldFlow = async {
            assertFailsWith<IllegalStateException> {
                oldLogical.subscribe("subject").toList()
            }
        }
        while (!messageHandler.isCaptured) yield()
        firstStatus = Connection.Status.CLOSED
        @Suppress("DEPRECATION")
        connectionListener.captured.connectionEvent(first, ConnectionListener.Events.CLOSED)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { oldFlow.await() }
        }

        val newLogical = manager.openConnection()
        newLogical.publish("subject", byteArrayOf(1))
        newLogical.close()
        manager.close()

        verify(exactly = 1) { replacement.publish("subject", byteArrayOf(1)) }
    }

    @Test
    fun `physical connection closing during installation is rejected`() = runTest {
        val closing = mockk<Connection>()
        val replacement = mockk<Connection>()
        val statusChecks = AtomicInteger()
        every { closing.status } answers {
            if (statusChecks.getAndIncrement() == 0) Connection.Status.CONNECTED else Connection.Status.CLOSED
        }
        every { replacement.status } returns Connection.Status.CONNECTED
        every { closing.addConnectionListener(any()) } just runs
        every { replacement.addConnectionListener(any()) } just runs
        every { closing.removeConnectionListener(any()) } just runs
        every { replacement.removeConnectionListener(any()) } just runs
        every { closing.close() } just runs
        every { replacement.close() } just runs
        every { replacement.publish(any(), any<ByteArray>()) } just runs
        val invocation = AtomicInteger()
        val manager = NatsConnectionPool(maxConnections = 1) {
            if (invocation.getAndIncrement() == 0) closing else replacement
        }

        val logical = manager.openConnection()
        logical.publish("subject", byteArrayOf(1))
        logical.close()
        manager.close()

        verify(exactly = 1) { closing.close() }
        verify(exactly = 1) { replacement.publish("subject", byteArrayOf(1)) }
    }
}
