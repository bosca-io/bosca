@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.nats.NatsPublishException
import bosca.analytics.transform.EventPipelineTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionManagerCallback
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.Headers
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EventProcessingRecoveryTest {
    private val health = EventProcessingHealth()
    private val application = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))
    private val context = EventPipelineContext(Headers.Empty)
    private val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val pool = mockk<ConnectionPool>()
        coEvery { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
    }

    @AfterTest
    fun teardown() = runBlocking {
        application.shutdown()
        ProviderRegistry.clear()
    }

    private fun service(
        repository: EventRepository,
        transforms: List<EventPipelineTransform> = emptyList(),
    ) = EventProcessingServiceImpl(application, repository, EventPipelineTransforms(transforms),
        EventProcessingConfiguration(workerCount = 1, channelCapacity = 1), health)

    @Test
    fun `asynchronous callers retain failure recovery and cancellation behavior`() = runBlocking {
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events) {
                when (events.sent) {
                    1L -> throw IOException("storage unavailable")
                    2L -> throw CancellationException("stop worker")
                }
            }
            override suspend fun flush() = Unit
        })
        processing.queue(context, events)
        withTimeout(5000) { while (health.isReady) delay(10) }
        processing.queueAndWait(context, events.copy(sent = 3))
        assertTrue(health.isReady)
        processing.queue(context, events.copy(sent = 2))
        withTimeout(5000) { while (health.isReady) delay(10) }
        assertFalse(health.isReady)
    }

    @Test
    fun `NATS publish failure reaches the caller and recovers independently of request processing`() = runBlocking {
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events) {
                health.natsFailed()
                throw NatsPublishException(IOException("NATS unavailable"))
            }
            override suspend fun flush() = Unit
        })
        assertFailsWith<NatsPublishException> { processing.queueAndWait(context, events) }
        assertFalse(health.isReady)
        health.natsRecovered()
        assertTrue(health.isReady)
    }

    @Test
    fun `waiting caller receives storage failure and the worker handles the next request`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = IOException("storage unavailable")
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events) {
                if (events.sent == 1L) {
                    entered.complete(Unit)
                    release.await()
                    throw failure
                }
            }
            override suspend fun flush() = Unit
        })
        supervisorScope {
            val pending = async { processing.queueAndWait(context, events) }
            withTimeout(5000) { entered.await() }
            assertFalse(pending.isCompleted)
            release.complete(Unit)
            assertEquals(failure.message, assertFailsWith<IOException> { pending.await() }.message)
        }
        assertFalse(health.isReady)
        processing.queueAndWait(context, events.copy(sent = 2))
        assertTrue(health.isReady)
    }

    @Test
    fun `cancelling a waiting caller does not cancel the worker`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stored = CopyOnWriteArrayList<Long>()
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events) {
                entered.complete(Unit)
                release.await()
                stored += events.sent
            }
            override suspend fun flush() = Unit
        })
        val pending = async { processing.queueAndWait(context, events) }
        withTimeout(5000) { entered.await() }
        pending.cancelAndJoin()
        release.complete(Unit)
        withTimeout(5000) { processing.queueAndWait(context, events.copy(sent = 2)) }
        assertEquals(listOf(1L, 2L), stored)
        assertTrue(health.isReady)
    }

    @Test
    fun `worker cancellation reaches its waiting caller`() = runBlocking {
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events): Unit = throw CancellationException("stop")
            override suspend fun flush() = Unit
        })
        assertEquals("stop", assertFailsWith<CancellationException> { withTimeout(5000) { processing.queueAndWait(context, events) } }.message)
        assertFalse(health.isReady)
    }

    @Test
    fun `cache cleanup failure does not kill worker or replay stored batch`() = runBlocking {
        val stored = CopyOnWriteArrayList<Long>()
        val cleanup = CompletableDeferred<Unit>()
        val transforms = AtomicInteger()
        val transform = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                if (transforms.incrementAndGet() == 1) connection().addCallback(object : ConnectionManagerCallback {
                    override suspend fun onCommit() = Unit
                    override suspend fun onRelease() {
                        cleanup.complete(Unit)
                        throw IOException("NATS cache flush failed")
                    }
                })
                return events
            }
        }
        val processing = service(object : EventRepository {
            override suspend fun process(events: Events) { stored += events.sent }
            override suspend fun flush() = Unit
        }, listOf(transform))
        assertFailsWith<IOException> { processing.queueAndWait(context, events) }
        withTimeout(5000) { cleanup.await() }
        withTimeout(5000) { while (health.isReady) delay(10) }
        assertFalse(health.isReady)
        assertTrue(health.isLive)
        processing.queue(context, events.copy(sent = 2))
        application.shutdown()
        assertTrue(health.isReady)
        assertEquals(listOf(1L, 2L), stored)
        assertEquals(2, transforms.get())
    }

}
