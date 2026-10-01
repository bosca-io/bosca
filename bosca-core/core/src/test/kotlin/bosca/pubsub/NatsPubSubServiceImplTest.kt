package bosca.pubsub

import bosca.nats.NatsConnectionPool
import io.mockk.*
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.MessageHandler
import io.nats.client.Subscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NatsPubSubServiceImplTest {

    private val connection = mockk<Connection>()
    private val natsConnection = NatsConnectionPool(connection)
    private val json = Json
    private lateinit var pubSubService: NatsPubSubServiceImpl

    @BeforeTest
    fun setup() {
        pubSubService = NatsPubSubServiceImpl(json, natsConnection)
    }

    @Test
    fun publishMessage() = runTest {
        val subject = "test.subject"
        val message = "test message"
        
        every { connection.publish(subject, any<ByteArray>()) } just Runs

        pubSubService.publish(subject, String.serializer(), message)

        verify {
            connection.publish(subject, match { 
                it.toString(Charsets.UTF_8) == "\"test message\"" 
            })
        }
    }

    @Test
    fun subscribeMessage() = runTest {
        val subject = "test.subject"
        val message = "test message"
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        val slot = slot<MessageHandler>()

        every { connection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe(subject, capture(slot)) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { connection.closeDispatcher(dispatcher) } just Runs

        val resultFlow = pubSubService.subscribe(subject, String.serializer())
        
        launch {
            // Wait for the flow to start and dispatcher to be created
            while (!slot.isCaptured) {
                yield()
            }

            val msg = mockk<io.nats.client.Message>()
            every { msg.subject } returns subject
            every { msg.data } returns "\"$message\"".toByteArray(Charsets.UTF_8)
            
            slot.captured.onMessage(msg)
        }

        val result = resultFlow.first()
        assertEquals(subject, result.channel)
        assertEquals(message, result.message)

        verify { dispatcher.unsubscribe(subscription) }
        verify(exactly = 0) { connection.closeDispatcher(dispatcher) }
        natsConnection.close()
        verify { connection.closeDispatcher(dispatcher) }
        verify(exactly = 0) { connection.close() }
    }

    @Test
    fun `malformed message does not terminate subscription`() = runTest {
        val subject = "test.subject"
        val dispatcher = mockk<Dispatcher>()
        val subscription = mockk<Subscription>()
        val handler = slot<MessageHandler>()
        every { connection.createDispatcher() } returns dispatcher
        every { dispatcher.subscribe(subject, capture(handler)) } returns subscription
        every { dispatcher.unsubscribe(subscription) } returns dispatcher
        every { connection.closeDispatcher(dispatcher) } just Runs

        val received = launch {
            val result = pubSubService.subscribe(subject, String.serializer()).first()
            assertEquals("valid", result.message)
        }
        while (!handler.isCaptured) yield()
        val malformed = mockk<io.nats.client.Message>()
        every { malformed.subject } returns subject
        every { malformed.data } returns "not-json".toByteArray()
        handler.captured.onMessage(malformed)
        val valid = mockk<io.nats.client.Message>()
        every { valid.subject } returns subject
        every { valid.data } returns "\"valid\"".toByteArray()
        handler.captured.onMessage(valid)

        received.join()
        verify(exactly = 1) { dispatcher.subscribe(subject, any<MessageHandler>()) }
    }
}
