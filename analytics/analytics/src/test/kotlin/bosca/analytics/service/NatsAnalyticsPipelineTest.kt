@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.Device
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.CompositeEventRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class NatsAnalyticsPipelineTest {
    private val health = EventProcessingHealth()
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
    private val eventContext = EventContext(
        appId = "app", appVersion = "1", sessionId = "session", userId = "user",
        device = Device("installation", "test", "test", "JVM", "en", "test", "UTC", "server", "1"),
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { requestCacheSerializer }
        provides<ConnectionPool> { connectionPool }
        coEvery { connectionPool.connection() } returns connectionManager
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStreamManagement(any<io.nats.client.JetStreamOptions>()) } returns management
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
    fun `readiness records connection failures and recovers after JetStream responds`() = runBlocking {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } throws IOException("NATS unavailable")
        NatsEventRepository.checkReadiness(nats, health)
        assertFalse(health.isReady)

        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        NatsEventRepository.checkReadiness(nats, health)
        assertTrue(health.isReady)
    }

    @Test
    fun `readiness times out connection acquisition but preserves caller cancellation`() = runTest {
        val pool = mockk<NatsConnectionPool>()
        coEvery { pool.systemConnection() } coAnswers { awaitCancellation() }
        NatsEventRepository.checkReadiness(pool, health)
        assertFalse(health.isReady)

        health.natsRecovered()
        val started = CompletableDeferred<Unit>()
        coEvery { pool.systemConnection() } coAnswers { started.complete(Unit); awaitCancellation() }
        val request = async { NatsEventRepository.checkReadiness(pool, health) }
        started.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(health.isReady)
    }

    @Test
    fun `publisher deduplicates retries only with an installation and event ID`() = runTest {
        val options = mutableListOf<io.nats.client.PublishOptions>()
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        every { jetStream.publishAsync(any(), any<ByteArray>(), capture(options)) } returns CompletableFuture.completedFuture(mockk<PublishAck>())
        val repository = NatsEventRepository(nats, json, health)
        val batch = events.copy(context = eventContext, events = listOf(events.events.first()))
        repository.process(batch)
        repository.process(batch.copy(sent = 100, sentMicros = 20, received = 200, receivedMicros = 30))
        assertEquals(options[0].messageId, options[1].messageId)
        assertTrue(options[0].messageId.isNotBlank())

        val anonymous = batch.copy(events = listOf(batch.events.first().copy(clientId = null), batch.events.first().copy(clientId = "")))
        repository.process(anonymous)
        repository.process(anonymous.copy(received = 500, receivedMicros = 12))
        repository.process(batch.copy(events = listOf(batch.events.first().copy(clientId = " "))))
        repository.process(batch.copy(context = null))
        repository.process(batch.copy(context = eventContext.copy(device = eventContext.device.copy(installationId = " "))))
        options.drop(2).forEach { assertNull(it.messageId) }
    }

    @Test
    fun `publisher scopes IDs by app installation user and event without separator collisions`() = runTest {
        val options = mutableListOf<io.nats.client.PublishOptions>()
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        every { jetStream.publishAsync(any(), any<ByteArray>(), capture(options)) } returns CompletableFuture.completedFuture(mockk<PublishAck>())
        val repository = NatsEventRepository(nats, json, health)
        val batch = events.copy(context = eventContext, events = listOf(events.events.first()))
        val distinctBatches = listOf(
            batch,
            batch.copy(context = eventContext.copy(appId = "other-app")),
            batch.copy(context = eventContext.copy(device = eventContext.device.copy(installationId = "other-installation"))),
            batch.copy(context = eventContext.copy(userId = "other-user")),
            batch.copy(context = eventContext.copy(userId = null)),
            batch.copy(context = eventContext.copy(userId = "")),
            batch.copy(events = listOf(events.events.last())),
            batch.copy(context = eventContext.copy(appId = "a:b", device = eventContext.device.copy(installationId = "c"))),
            batch.copy(context = eventContext.copy(appId = "a", device = eventContext.device.copy(installationId = "b:c"))),
            batch.copy(context = eventContext.copy(device = eventContext.device.copy(installationId = "installation:user"), userId = "id")),
            batch.copy(context = eventContext.copy(userId = "user:id")),
            batch.copy(context = eventContext.copy(userId = "user:event"), events = listOf(events.events.first().copy(clientId = "id"))),
            batch.copy(events = listOf(events.events.first().copy(clientId = "event:id"))),
        )
        distinctBatches.forEach { repository.process(it) }
        val messageIds = options.map { requireNotNull(it.messageId) }
        assertEquals(distinctBatches.size, messageIds.toSet().size)

        distinctBatches.forEach { repository.process(it.copy(sent = 100, received = 200, receivedMicros = 30)) }
        assertEquals(messageIds, options.drop(distinctBatches.size).map { it.messageId })
    }

    @Test
    fun `consumer does not acknowledge primary storage or request cleanup failures`() = runTest {
        val primary = mockk<EventRepository>()
        coEvery { primary.process(any()) } throws IOException("disk unavailable")
        val composite = CompositeEventRepository(primary, emptyList())
        val consumer = NatsEventConsumer(nats, json, composite, EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
        val failedStorage = message()
        consumer.processMessage(failedStorage)
        verify(exactly = 0) { failedStorage.ack() }
        verify(exactly = 1) { failedStorage.nak() }

        coJustRun { primary.process(any()) }
        coEvery { connectionManager.release() } throws IOException("cleanup unavailable")
        val failedCleanup = message()
        consumer.processMessage(failedCleanup)
        verify(exactly = 0) { failedCleanup.ack() }
        verify(exactly = 1) { failedCleanup.nak() }
    }

    @Test
    fun `publisher initializes stream once and publishes one envelope per event`() = runTest {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        every { jetStream.publishAsync(any(), any<ByteArray>(), any<io.nats.client.PublishOptions>()) } returns CompletableFuture.completedFuture(mockk<PublishAck>())
        val repository = NatsEventRepository(nats, json, health)

        repository.process(events)
        repository.process(events.copy(events = listOf(events.events.first())))
        repository.flush()

        verify(exactly = 3) { jetStream.publishAsync(NatsEventRepository.STREAM_SUBJECT, any<ByteArray>(), any<io.nats.client.PublishOptions>()) }
        verify(exactly = 1) { connection.jetStreamManagement() }
    }

    @Test
    fun `publisher sends the whole batch before waiting and requires every acknowledgment`() = runBlocking {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        val responses = events.events.map { CompletableFuture<PublishAck>() }
        val published = CompletableDeferred<Unit>()
        var count = 0
        every { jetStream.publishAsync(any(), any<ByteArray>(), any<io.nats.client.PublishOptions>()) } answers {
            responses[count++].also { if (count == responses.size) published.complete(Unit) }
        }
        val repository = NatsEventRepository(nats, json, health)
        val pending = async { repository.process(events) }

        withTimeout(5000) { published.await() }
        assertFalse(pending.isCompleted)
        responses.first().complete(mockk<PublishAck>())
        assertNull(withTimeoutOrNull(100) { pending.await() })
        responses.last().complete(mockk<PublishAck>())
        withTimeout(5000) { pending.await() }
        assertTrue(health.isReady)
    }

    @Test
    fun `publisher reports NATS future cancellation as a failure and publishes subsequent batches`() = runTest {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        val response = CompletableFuture<PublishAck>()
        val cancelled = response.thenApply { it }
        response.cancel(false)
        every { jetStream.publishAsync(any(), any<ByteArray>(), any<io.nats.client.PublishOptions>()) } returns cancelled andThen
            CompletableFuture.completedFuture(mockk<PublishAck>())
        val repository = NatsEventRepository(nats, json, health)
        val batch = events.copy(events = events.events.take(1))

        assertFailsWith<IOException> { repository.process(batch) }
        assertFalse(health.isReady)
        repository.process(batch)
        assertTrue(health.isReady)

        verify(exactly = 2) { jetStream.publishAsync(NatsEventRepository.STREAM_SUBJECT, any<ByteArray>(), any<io.nats.client.PublishOptions>()) }
    }

    @Test
    fun `publisher preserves caller cancellation while awaiting NATS`() = runTest {
        every { management.getStreamInfo(NatsEventRepository.STREAM_NAME) } returns mockk()
        val published = CompletableDeferred<Unit>()
        every { jetStream.publishAsync(any(), any<ByteArray>(), any<io.nats.client.PublishOptions>()) } answers {
            published.complete(Unit)
            CompletableFuture<PublishAck>()
        }
        val repository = NatsEventRepository(nats, json, health)
        val pending = async { repository.process(events) }

        published.await()
        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
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
        val repository = NatsEventRepository(nats, json, health)
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
