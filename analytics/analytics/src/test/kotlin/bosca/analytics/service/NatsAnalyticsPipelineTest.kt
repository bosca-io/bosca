@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.analytics.transform.EventPipelineTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.PublishAck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NatsAnalyticsPipelineTest {
    private val json = Json { encodeDefaults = true }
    private val connection = mockk<Connection>()
    private val management = mockk<JetStreamManagement>()
    private val jetStream = mockk<JetStream>()
    private val nats = NatsConnectionPool(connection)
    private val eventRepository = mockk<EventRepository>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val connectionPool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val events = Events(
        events = listOf(
            Event(created = 1, type = EventType.Session, clientId = "one"),
            Event(created = 2, type = EventType.Session, clientId = "two"),
        ),
        sent = 3,
        sentMicros = 4,
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { requestCacheSerializer }
        provides<ConnectionPool> { connectionPool }
        coEvery { connectionPool.connection() } returns connectionManager
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStream() } returns jetStream
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun message(payload: String = json.encodeToString(Events.serializer(), events)): Message =
        mockk<Message>(relaxed = true) {
            every { data } returns payload.toByteArray()
        }

    @Test
    fun `publisher initializes stream once and publishes one envelope per event`() = runTest {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        every { jetStream.publishAsync(any(), any<ByteArray>()) } returns CompletableFuture.completedFuture(mockk<PublishAck>())
        val repository = NatsEventRepository(nats, json)

        repository.process(events)
        repository.process(events.copy(events = listOf(events.events.first())))
        repository.flush()

        verify(exactly = 3) { jetStream.publishAsync(NatsEventRepository.STREAM_SUBJECT, any<ByteArray>()) }
        verify(exactly = 1) { connection.jetStreamManagement() }
    }

    @Test
    fun `concurrent publishers reuse initialization completed by the lock holder`() = runTest {
        val initializationEntered = CountDownLatch(1)
        val allowInitialization = CountDownLatch(1)
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } answers {
            initializationEntered.countDown()
            check(allowInitialization.await(10, TimeUnit.SECONDS))
            mockk()
        }
        val repository = NatsEventRepository(nats, json)
        val noEvents = events.copy(events = emptyList())

        val first = async(Dispatchers.Default) { repository.process(noEvents) }
        check(withContext(Dispatchers.IO) { initializationEntered.await(10, TimeUnit.SECONDS) })
        val second = async(Dispatchers.Default) { repository.process(noEvents) }
        try {
            // The first publisher holds the initialization mutex at the latch, allowing the second
            // publisher to pass the fast-path check and wait for that same initialization.
            delay(100)
        } finally {
            allowInitialization.countDown()
        }
        first.await()
        second.await()

        verify(exactly = 1) { connection.jetStreamManagement() }
    }

    @Test
    fun `ensureStream creates missing work queue stream and propagates other API errors`() {
        val missing = mockk<JetStreamApiException>()
        every { missing.apiErrorCode } returns 10059
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } throws missing
        every { management.addStream(any<io.nats.client.api.StreamConfiguration>()) } returns mockk()

        NatsEventRepository.ensureStream(management)

        verify(exactly = 1) { management.addStream(match { it.name == NatsEventRepository.STREAM_NAME }) }

        val other = mockk<JetStreamApiException>()
        every { other.apiErrorCode } returns 10000
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } throws other
        assertSame(other, assertFailsWith<JetStreamApiException> { NatsEventRepository.ensureStream(management) })
    }

    @Test
    fun `consumer processMessage applies transforms and acknowledges successful storage`() = runTest {
        val transformed = events.copy(sent = 99)
        val transform = mockk<EventPipelineTransform>()
        coEvery { transform.transform(any(), events) } returns transformed
        coJustRun { eventRepository.process(transformed) }
        val message = message()
        val consumer = NatsEventConsumer(nats, json, eventRepository, EventPipelineTransforms(listOf(transform)), EventProcessingConfiguration())

        consumer.processMessage(message)

        verify(exactly = 1) { message.ack() }
        verify(exactly = 0) { message.nak() }
    }

    @Test
    fun `consumer NAKs decode transform and repository failures`() = runTest {
        val invalid = message("not-json")
        val consumer = NatsEventConsumer(nats, json, eventRepository, EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
        consumer.processMessage(invalid)
        verify(exactly = 1) { invalid.nak() }

        val transform = mockk<EventPipelineTransform>()
        coEvery { transform.transform(any(), any()) } throws IllegalStateException("transform")
        val transformMessage = message()
        NatsEventConsumer(nats, json, eventRepository, EventPipelineTransforms(listOf(transform)), EventProcessingConfiguration())
            .processMessage(transformMessage)
        verify(exactly = 1) { transformMessage.nak() }

        coEvery { eventRepository.process(any()) } throws IllegalStateException("repository")
        val repositoryMessage = message()
        consumer.processMessage(repositoryMessage)
        verify(exactly = 1) { repositoryMessage.nak() }
    }

    @Test
    fun `consumer preserves cancellation from transform`() = runTest {
        val transform = mockk<EventPipelineTransform>()
        coEvery { transform.transform(any(), any()) } throws CancellationException("cancelled")
        val consumer = NatsEventConsumer(nats, json, eventRepository, EventPipelineTransforms(listOf(transform)), EventProcessingConfiguration())

        assertFailsWith<CancellationException> { consumer.processMessage(message()) }
    }

    @Test
    fun `consumer run drains fetched messages and stops on fetch cancellation`() = runTest {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        val subscription = mockk<JetStreamSubscription>()
        every { jetStream.subscribe(any(), any<PullSubscribeOptions>()) } returns subscription
        val message = message()
        every { subscription.fetch(1, any<Duration>()) } returns listOf(message) andThenThrows
            IllegalStateException("temporary") andThenThrows CancellationException("stop")
        coJustRun { eventRepository.process(events) }
        val consumer = NatsEventConsumer(
            nats,
            json,
            eventRepository,
            EventPipelineTransforms(emptyList()),
            EventProcessingConfiguration(workerCount = 1, channelCapacity = 1, natsBatchSize = 1),
        )

        consumer.run()

        verify(exactly = 1) { message.ack() }
    }
}
