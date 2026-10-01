package bosca.pubsub

import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests verifying the [NatsPubSubServiceImpl] against a real NATS server
 * managed by TestContainers.
 *
 * These tests exercise actual NATS publish/subscribe messaging with serialization,
 * verifying that messages round-trip correctly through the NATS transport layer.
 */
class NatsPubSubEndToEndTest {

    @Serializable
    data class TestEvent(val id: Int, val payload: String)

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var pubSubService: NatsPubSubServiceImpl
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()

        natsPool = natsContainer.newConnectionPool(5)
        pubSubService = NatsPubSubServiceImpl(json, natsPool)
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@NatsPubSubEndToEndTest::natsPool.isInitialized) natsPool.close()
        if (this@NatsPubSubEndToEndTest::natsContainer.isInitialized) natsContainer.stop()
    }

    @Test
    fun `publish and subscribe round-trips a string message`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.string.${System.nanoTime()}"

            val received = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, String.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channel, String.serializer(), "hello-nats")

            val message = received.await()
            assertEquals(channel, message.channel)
            assertEquals("hello-nats", message.message)
        }
    }

    @Test
    fun `publish and subscribe round-trips a serializable object`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.object.${System.nanoTime()}"
            val event = TestEvent(id = 42, payload = "test-payload")

            val received = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, TestEvent.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channel, TestEvent.serializer(), event)

            val message = received.await()
            assertEquals(channel, message.channel)
            assertEquals(42, message.message.id)
            assertEquals("test-payload", message.message.payload)
        }
    }

    @Test
    fun `multiple messages arrive in order on the same channel`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.multi.${System.nanoTime()}"
            val count = 5

            val received = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, TestEvent.serializer())
                    .take(count)
                    .toList()
            }

            delay(500)

            repeat(count) { i ->
                pubSubService.publish(channel, TestEvent.serializer(), TestEvent(id = i, payload = "msg-$i"))
            }

            val messages = received.await()
            assertEquals(count, messages.size)
            messages.forEachIndexed { index, msg ->
                assertEquals(index, msg.message.id)
                assertEquals("msg-$index", msg.message.payload)
            }
        }
    }

    @Test
    fun `subscribers on different channels receive only their messages`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channelA = "test.isolation.a.${System.nanoTime()}"
            val channelB = "test.isolation.b.${System.nanoTime()}"

            val receivedA = async(Dispatchers.Default) {
                pubSubService.subscribe(channelA, String.serializer()).first()
            }
            val receivedB = async(Dispatchers.Default) {
                pubSubService.subscribe(channelB, String.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channelA, String.serializer(), "for-a")
            pubSubService.publish(channelB, String.serializer(), "for-b")

            val messageA = receivedA.await()
            val messageB = receivedB.await()

            assertEquals("for-a", messageA.message)
            assertEquals(channelA, messageA.channel)
            assertEquals("for-b", messageB.message)
            assertEquals(channelB, messageB.channel)
        }
    }

    @Test
    fun `multiple subscribers on the same channel each receive the message`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.fanout.${System.nanoTime()}"

            val sub1 = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, String.serializer()).first()
            }
            val sub2 = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, String.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channel, String.serializer(), "fanout-msg")

            val msg1 = sub1.await()
            val msg2 = sub2.await()

            assertEquals("fanout-msg", msg1.message)
            assertEquals("fanout-msg", msg2.message)
        }
    }

    @Test
    fun `more subscribers than physical connections remain active concurrently`(): Unit = runBlocking {
        val multiplexPool = natsContainer.newConnectionPool(2)
        val multiplexService = NatsPubSubServiceImpl(json, multiplexPool)
        try {
            withTimeout(60_000) {
                val channel = "test.multiplex.${System.nanoTime()}"
                val subscriberCount = multiplexPool.maxConnections + 1
                val ready = List(subscriberCount) { CompletableDeferred<Unit>() }
                val subscribers = List(subscriberCount) { index ->
                    async(Dispatchers.Default) {
                        multiplexService.subscribe(channel, String.serializer()) {
                            ready[index].complete(Unit)
                        }.first()
                    }
                }

                withTimeout(30_000) { ready.awaitAll() }
                assertTrue(multiplexPool.activeDispatcherCount <= multiplexPool.maxConnections)
                multiplexService.publish(channel, String.serializer(), "multiplexed")

                val messages = withTimeout(30_000) { subscribers.awaitAll() }
                messages.forEach { message ->
                    assertEquals("multiplexed", message.message)
                }
            }
        } finally {
            multiplexPool.close()
        }
    }

    @Test
    fun `publish and subscribe handles special characters in payload`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.special.${System.nanoTime()}"
            val specialPayload = "Hello \"world\" \uD83C\uDF0D\nnewline\ttab\u0000null"

            val received = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, String.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channel, String.serializer(), specialPayload)

            val message = received.await()
            assertEquals(specialPayload, message.message)
        }
    }

    @Test
    fun `publish and subscribe handles empty string`(): Unit = runBlocking {
        withTimeout(30_000) {
            val channel = "test.empty.${System.nanoTime()}"

            val received = async(Dispatchers.Default) {
                pubSubService.subscribe(channel, String.serializer()).first()
            }

            delay(500)

            pubSubService.publish(channel, String.serializer(), "")

            val message = received.await()
            assertTrue(message.message.isEmpty())
        }
    }
}
