package bosca.analytics.queue

import bosca.analytics.service.EventProcessingHealth
import bosca.nats.NatsConnectionPool
import io.mockk.*
import io.nats.client.*
import io.nats.client.api.StreamInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration

class NatsAnalyticsReconnectTest {
    @Test fun `batch receive rejects invalid limits and handles an empty single receive`(): Unit = runBlocking {
        val pool = mockk<NatsConnectionPool>()
        val connection = mockk<Connection>()
        coEvery { pool.systemConnection() } returns connection
        val management = mockk<JetStreamManagement>()
        every { management.getStreamInfo(any<String>()) } returns mockk<StreamInfo>()
        val jetStream = mockk<JetStream>()
        val subscription = mockk<JetStreamSubscription>(relaxed = true)
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStream() } returns jetStream
        every { jetStream.subscribe(any<String>(), any<PullSubscribeOptions>()) } returns subscription
        every { subscription.fetch(any<Int>(), any<java.time.Duration>()) } returns emptyList()
        val queue = NatsAnalyticsEventQueue(pool, Json, EventProcessingHealth())
        try {
            assertFailsWith<IllegalArgumentException> { queue.receiveBatch(0, Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { queue.receiveBatch(-1, Duration.ZERO) }
            coVerify(exactly = 0) { pool.systemConnection() }
            assertNull(queue.receive(Duration.ZERO))
            assertEquals(emptyList(), queue.receiveBatch(100, Duration.ZERO))
            verify { subscription.fetch(1, java.time.Duration.ofMillis(1)) }
            verify { subscription.fetch(100, java.time.Duration.ofMillis(1)) }
        } finally { queue.shutdown() }
    }

    @Test fun `replaced connection releases the old subscription and reuses the replacement`(): Unit = runBlocking {
        val pool = mockk<NatsConnectionPool>()
        val first = mockk<Connection>()
        val second = mockk<Connection>()
        coEvery { pool.systemConnection() } returnsMany listOf(first, second, second)
        val management = mockk<JetStreamManagement>()
        every { management.getStreamInfo(any<String>()) } returns mockk<StreamInfo>()
        val firstJetStream = mockk<JetStream>()
        val secondJetStream = mockk<JetStream>()
        val firstSubscription = mockk<JetStreamSubscription>(relaxed = true)
        val secondSubscription = mockk<JetStreamSubscription>(relaxed = true)
        every { first.jetStreamManagement() } returns management
        every { second.jetStreamManagement() } returns management
        every { first.jetStream() } returns firstJetStream
        every { second.jetStream() } returns secondJetStream
        every { firstJetStream.subscribe(any<String>(), any<PullSubscribeOptions>()) } returns firstSubscription
        every { secondJetStream.subscribe(any<String>(), any<PullSubscribeOptions>()) } returns secondSubscription
        val queue = NatsAnalyticsEventQueue(pool, Json, EventProcessingHealth())
        try {
            assertSame(firstSubscription, queue.subscription())
            assertSame(secondSubscription, queue.subscription())
            assertSame(secondSubscription, queue.subscription())
            verify(exactly = 1) { firstSubscription.unsubscribe() }
            verify(exactly = 1) { secondJetStream.subscribe(any<String>(), any<PullSubscribeOptions>()) }
        } finally { queue.shutdown() }
        verify(exactly = 1) { secondSubscription.unsubscribe() }
    }
}
