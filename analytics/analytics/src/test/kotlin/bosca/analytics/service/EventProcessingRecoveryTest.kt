@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
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
        processing.queue(context, events)
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
