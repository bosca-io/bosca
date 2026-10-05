package bosca.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.MessageHandler
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.PushSubscribeOptions
import io.nats.client.Subscription
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BoscaNatsConnectionTest {

    @Test
    fun `publish uses physical connection while close retains physical ownership`() = runTest {
        val physicalConnection = mockk<Connection>()
        every { physicalConnection.publish("subject", any<ByteArray>()) } just runs
        val connection = BoscaNatsConnection(physicalConnection)
        val data = byteArrayOf(1, 2, 3)

        assertSame(physicalConnection, connection.withConnection { it })
        connection.publish("subject", data)
        connection.close()
        connection.close()

        verify(exactly = 1) { physicalConnection.publish("subject", data) }
        verify(exactly = 0) { physicalConnection.close() }
        assertFailsWith<IllegalStateException> { connection.publish("subject", data) }
        assertFailsWith<IllegalStateException> { connection.withConnection { it } }
    }

    @Test
    fun `multiple flows share one dispatcher and unsubscribe independently`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val firstSubscription = mockk<Subscription>()
        val secondSubscription = mockk<Subscription>()
        val handlers = mutableListOf<MessageHandler>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe(any(), capture(handlers)) } returnsMany
            listOf(firstSubscription, secondSubscription)
        every { dispatcher.unsubscribe(any<Subscription>()) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val connection = BoscaNatsConnection(physicalConnection)
        val firstMessage = mockk<Message>()
        val secondMessage = mockk<Message>()

        val first = async { connection.subscribe("first").first() }
        val second = async { connection.subscribe("second").first() }
        while (handlers.size < 2) yield()
        handlers[0].onMessage(firstMessage)
        handlers[1].onMessage(secondMessage)

        assertSame(firstMessage, first.await())
        assertSame(secondMessage, second.await())
        verify(exactly = 1) { physicalConnection.createDispatcher() }
        verify(exactly = 1) { dispatcher.unsubscribe(firstSubscription) }
        verify(exactly = 1) { dispatcher.unsubscribe(secondSubscription) }

        connection.close()
        verify(exactly = 1) { physicalConnection.closeDispatcher(dispatcher) }
    }

    @Test
    fun `logical handles on one physical connection share one dispatcher`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val firstSubscription = mockk<Subscription>()
        val secondSubscription = mockk<Subscription>()
        val handlers = mutableListOf<MessageHandler>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe(any(), capture(handlers)) } returnsMany
            listOf(firstSubscription, secondSubscription)
        every { dispatcher.unsubscribe(any<Subscription>()) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val physical = BoscaNatsPhysicalConnection(physicalConnection)
        val firstConnection = BoscaNatsConnection(physical)
        val secondConnection = BoscaNatsConnection(physical)
        val firstMessage = mockk<Message>()
        val secondMessage = mockk<Message>()

        val first = async { firstConnection.subscribe("first").first() }
        val second = async { secondConnection.subscribe("second").first() }
        while (handlers.size < 2) yield()
        handlers[0].onMessage(firstMessage)
        handlers[1].onMessage(secondMessage)

        assertSame(firstMessage, first.await())
        assertSame(secondMessage, second.await())
        verify(exactly = 1) { physicalConnection.createDispatcher() }

        firstConnection.close()
        secondConnection.close()
        verify(exactly = 0) { physicalConnection.closeDispatcher(dispatcher) }
        physical.close()
        verify(exactly = 1) { physicalConnection.closeDispatcher(dispatcher) }
    }

    @Test
    fun `closing logical connection closes active flows and their subscriptions`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        val handler = slot<MessageHandler>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe("subject", capture(handler)) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val connection = BoscaNatsConnection(physicalConnection)

        val messages = async { connection.subscribe("subject").toList() }
        while (!handler.isCaptured) yield()
        connection.close()

        messages.await()
        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        verify(exactly = 1) { physicalConnection.closeDispatcher(dispatcher) }
        verify(exactly = 0) { physicalConnection.close() }
    }

    @Test
    fun `subscription readiness failure unsubscribes the registered consumer`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe("subject", any<MessageHandler>()) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val connection = BoscaNatsConnection(physicalConnection)

        assertFailsWith<IllegalStateException> {
            connection.subscribe("subject") { error("flush failed") }.toList()
        }

        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        connection.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `consumer buffer overflow drops excess messages without terminating the flow`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        val handler = slot<MessageHandler>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe("subject", capture(handler)) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val connection = BoscaNatsConnection(physicalConnection)
        val bufferedMessage = mockk<Message>()
        val finalMessage = mockk<Message>()
        every { bufferedMessage.subject } returns "subject"
        every { finalMessage.subject } returns "subject"
        val finalDelivery = async {
            connection.subscribe("subject").first { it === finalMessage }
        }
        while (!handler.isCaptured) yield()
        repeat(1_000) { handler.captured.onMessage(bufferedMessage) }
        runCurrent()
        handler.captured.onMessage(finalMessage)

        assertSame(finalMessage, withTimeout(5_000) { finalDelivery.await() })
        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        connection.close()
    }

    @Test
    fun `jetstream flow shares logical dispatcher and leaves acknowledgement to collector`() = runTest {
        val physicalConnection = mockk<Connection>()
        val dispatcher = mockk<Dispatcher>()
        val jetStream = mockk<JetStream>()
        val coreSubscription = mockk<Subscription>()
        val subscription = mockk<JetStreamSubscription>()
        val options = mockk<PushSubscribeOptions>()
        val coreHandler = slot<MessageHandler>()
        val handler = slot<MessageHandler>()
        val coreMessage = mockk<Message>()
        val message = mockk<Message>()
        every { physicalConnection.createDispatcher() } returns dispatcher
        every { physicalConnection.jetStream() } returns jetStream
        every { dispatcher.subscribe("core", capture(coreHandler)) } returns coreSubscription
        every {
            jetStream.subscribe("subject", dispatcher, capture(handler), false, options)
        } returns subscription
        every { dispatcher.unsubscribe(coreSubscription) } returns dispatcher
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { physicalConnection.closeDispatcher(dispatcher) } just runs
        val connection = BoscaNatsConnection(physicalConnection)

        val coreReceived = async { connection.subscribe("core").first() }
        while (!coreHandler.isCaptured) yield()
        coreHandler.captured.onMessage(coreMessage)
        assertSame(coreMessage, coreReceived.await())

        val received = async { connection.subscribeJetStream("subject", options).first() }
        while (!handler.isCaptured) yield()
        handler.captured.onMessage(message)

        assertSame(message, received.await())
        verify(exactly = 0) { message.ack() }
        verify(exactly = 1) { dispatcher.unsubscribe(coreSubscription) }
        verify(exactly = 1) { dispatcher.unsubscribe(subscription) }
        verify(exactly = 1) { physicalConnection.createDispatcher() }
        connection.close()
        verify(exactly = 1) { physicalConnection.closeDispatcher(dispatcher) }
    }

    @Test
    fun `collecting after close fails without creating a dispatcher`() = runTest {
        val physicalConnection = mockk<Connection>()
        val connection = BoscaNatsConnection(physicalConnection)
        connection.close()

        assertFailsWith<IllegalStateException> {
            connection.subscribe("subject").first()
        }
        verify(exactly = 0) { physicalConnection.createDispatcher() }
    }
}
