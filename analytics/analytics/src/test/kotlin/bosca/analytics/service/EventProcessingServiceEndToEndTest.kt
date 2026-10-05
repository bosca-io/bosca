@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.events.AnalyticsScriptEvent
import bosca.analytics.model.Device
import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Geo
import bosca.analytics.repository.EventRepository
import bosca.analytics.transform.EventPipelineTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.analytics.transform.ScriptTransformPipelineTransform
import bosca.analytics.transform.ScriptTriggerPipelineTransform
import bosca.analytics.transform.geo.CloudflareGeoTransform
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.server.BoscaApplication
import bosca.server.Headers
import bosca.server.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end integration test for the event processing pipeline.
 *
 * Exercises [EventProcessingServiceImpl] with the real transform chain (geo enrichment plus the
 * platform-pipeline transforms) against a stubbed [PipelineService]: a triggered pipeline bound to
 * `analytics.transform.session` enriches session events with an element, mirroring what an
 * Execute Script node does in production. Pipeline storage and node execution have their own
 * coverage in the pipelines module.
 */
class EventProcessingServiceEndToEndTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private val pipelineService = mockk<PipelineService>()

    /** Records dispatched notification event names in place of the runner-backed implementation. */
    private val eventDispatcher = object : PipelineEventDispatcher {
        val dispatched = CopyOnWriteArrayList<String>()
        override suspend fun <T : bosca.events.Event> dispatch(
            eventName: String,
            event: T,
            serializer: KSerializer<T>,
        ) {
            dispatched.add(eventName)
        }
    }

    /**
     * Captures processed events for verification instead of persisting
     * to an external store like Iceberg.
     */
    class TestEventRepository : EventRepository {
        val processedBatches = CopyOnWriteArrayList<Events>()
        private val latches = CopyOnWriteArrayList<CountDownLatch>()

        fun expectBatch(): CountDownLatch {
            val latch = CountDownLatch(1)
            latches.add(latch)
            return latch
        }

        override suspend fun process(events: Events) {
            processedBatches.add(events)
            latches.forEach { it.countDown() }
        }

        override suspend fun flush() {}
    }

    @BeforeTest
    fun setup() {
        unmockkAll()
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { testJson }
        // Event processing wraps each batch in withRequestCache { withConnectionManager { } },
        // which resolve these three; none are exercised by the transforms under test.
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        provides<PipelineService>(singleton = true) { pipelineService }
        provides<PipelineEventDispatcher>(singleton = true) { eventDispatcher }

        // One triggered pipeline bound to session transforms; it enriches each event with an
        // element, standing in for an Execute Script node run by the real executor.
        val enrichPipeline = Pipeline(
            id = Uuid.random(),
            name = "Enrich Session Events",
            acceptedInputType = "analytics.transform.session",
            triggered = true,
        )
        coEvery { pipelineService.triggeredFor(any()) } returns emptyList()
        coEvery { pipelineService.triggeredFor("analytics.transform.session") } returns listOf(enrichPipeline)
        coEvery { pipelineService.run(enrichPipeline, any()) } coAnswers {
            val payload = secondArg<PipelineValue>().value as AnalyticsScriptEvent
            val enriched = payload.events.copy(
                events = payload.events.events.map { e ->
                    e.copy(element = Element(id = "script-enriched", type = "test-type"))
                }
            )
            PipelineValue.of(AnalyticsScriptEvent(enriched), AnalyticsScriptEvent.serializer())
        }
    }

    @AfterTest
    fun teardown() {
        eventDispatcher.dispatched.clear()
        ProviderRegistry.clear()
        unmockkAll()
    }

    private fun createPipeline(): List<EventPipelineTransform> {
        val transforms = mutableListOf<EventPipelineTransform>(CloudflareGeoTransform())
        for (type in EventType.entries) {
            transforms.add(ScriptTransformPipelineTransform("analytics.transform.${type.name.lowercase()}"))
        }
        for (type in EventType.entries) {
            transforms.add(ScriptTriggerPipelineTransform("analytics.notify.${type.name.lowercase()}"))
        }
        return transforms
    }

    private fun createService(
        eventRepository: TestEventRepository,
        transforms: List<EventPipelineTransform> = createPipeline()
    ): EventProcessingServiceImpl {
        val application = mockk<BoscaApplication> {
            every { onShutdown(any()) } just runs
        }
        val service = EventProcessingServiceImpl(application, eventRepository, EventPipelineTransforms(transforms), EventProcessingConfiguration())
        // Give the collector coroutine time to start on Dispatchers.IO
        Thread.sleep(200)
        return service
    }

    private fun createTestContext(headers: Headers = Headers.Empty) = EventPipelineContext(headers)

    private fun createTestEvents(
        eventType: EventType = EventType.Session,
        count: Int = 1
    ): Events {
        val now = System.currentTimeMillis()
        return Events(
            context = EventContext(
                appId = "test-app",
                appVersion = "1.0.0",
                device = Device(
                    installationId = "test-install-id",
                    manufacturer = "Test",
                    model = "TestDevice",
                    platform = "test",
                    primaryLocale = "en-US",
                    systemName = "TestOS",
                    timezone = "UTC",
                    type = "desktop",
                    version = "1.0"
                ),
                sessionId = "test-session-id"
            ),
            events = (1..count).map {
                Event(
                    created = now,
                    createdMicros = 0,
                    type = eventType,
                    clientId = "client-$it"
                )
            },
            sent = now,
            sentMicros = 0
        )
    }

    // ==================== Basic Pipeline Tests ====================

    @Test
    fun `queue sends events through pipeline to repository`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        service.queue(createTestContext(), createTestEvents())

        assertTrue(latch.await(30, TimeUnit.SECONDS), "events should be processed")
        assertEquals(1, repo.processedBatches.size)
    }

    @Test
    fun `queue sets received timestamps on events`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        service.queue(createTestContext(), createTestEvents())

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val result = repo.processedBatches.first()
        assertNotNull(result.received, "received timestamp should be set")
        assertNotNull(result.receivedMicros, "receivedMicros should be set")
        assertTrue(result.received!! > 0, "received should be a positive timestamp")
    }

    @Test
    fun `multiple batches are all processed`() = runBlocking {
        val repo = TestEventRepository()
        val batchCount = 5
        val latch = CountDownLatch(batchCount)
        // Override process to count down our latch
        val wrappedRepo = object : EventRepository {
            override suspend fun process(events: Events) {
                repo.process(events)
                latch.countDown()
            }
            override suspend fun flush() = repo.flush()
        }
        val application = mockk<BoscaApplication> {
            every { onShutdown(any()) } just runs
        }
        val service = EventProcessingServiceImpl(application, wrappedRepo, EventPipelineTransforms(createPipeline()), EventProcessingConfiguration())
        Thread.sleep(200)

        repeat(batchCount) {
            service.queue(createTestContext(), createTestEvents())
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "all $batchCount batches should be processed")
        assertEquals(batchCount, repo.processedBatches.size)
    }

    // ==================== Geo Enrichment Tests ====================

    @Test
    fun `cloudflare geo headers are applied to events`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        val headers = headersOf(
            "cf-ipcity" to listOf("San Francisco"),
            "cf-ipcountry" to listOf("US"),
            "cf-ipcontinent" to listOf("NA"),
            "cf-region" to listOf("California"),
            "cf-region-code" to listOf("CA"),
            "cf-postal-code" to listOf("94105"),
            "cf-timezone" to listOf("America/Los_Angeles"),
            "cf-iplongitude" to listOf("-122.4194"),
            "cf-iplatitude" to listOf("37.7749"),
        )

        service.queue(createTestContext(headers), createTestEvents())

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val result = repo.processedBatches.first()
        val geo = result.context?.geo
        assertNotNull(geo, "geo should be populated from CF headers")
        assertEquals("San Francisco", geo.city)
        assertEquals("US", geo.country)
        assertEquals("NA", geo.continent)
        assertEquals("California", geo.region)
        assertEquals("CA", geo.regionCode)
        assertEquals("94105", geo.postalCode)
        assertEquals("America/Los_Angeles", geo.timezone)
        assertEquals(-122.4194, geo.longitude)
        assertEquals(37.7749, geo.latitude)
    }

    // ==================== Pipeline Transform Tests ====================

    @Test
    fun `triggered pipeline enriches session events with element`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        service.queue(createTestContext(), createTestEvents(EventType.Session))

        assertTrue(latch.await(30, TimeUnit.SECONDS), "events should be processed")
        val stored = repo.processedBatches.first()
        assertEquals(1, stored.events.size)
        assertNotNull(stored.events.first().element, "pipeline should have added an element")
        assertEquals("script-enriched", stored.events.first().element?.id)
        assertEquals("test-type", stored.events.first().element?.type)
    }

    @Test
    fun `events without triggered pipelines pass through unchanged`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        // No pipeline is triggered for analytics.transform.interaction
        val transforms = listOf<EventPipelineTransform>(
            CloudflareGeoTransform(),
            ScriptTransformPipelineTransform("analytics.transform.interaction"),
            ScriptTriggerPipelineTransform("analytics.notify.interaction"),
        )
        val service = createService(repo, transforms)

        service.queue(createTestContext(), createTestEvents(EventType.Interaction))

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val stored = repo.processedBatches.first()
        assertEquals(1, stored.events.size)
        assertEquals(null, stored.events.first().element)
    }

    @Test
    fun `notification dispatch fires for processed event types`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        service.queue(createTestContext(), createTestEvents(EventType.Session))

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        assertTrue(
            eventDispatcher.dispatched.contains("analytics.notify.session"),
            "session notification should have been dispatched, got: ${eventDispatcher.dispatched}"
        )
    }

    // ==================== Full Pipeline Integration Tests ====================

    @Test
    fun `full pipeline applies geo then pipeline transform then stores`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        val headers = headersOf(
            "cf-ipcity" to listOf("TestCity"),
            "cf-ipcountry" to listOf("US"),
        )

        service.queue(createTestContext(headers), createTestEvents(EventType.Session))

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val stored = repo.processedBatches.first()

        // Verify received timestamp was set
        assertNotNull(stored.received, "received timestamp should be set")

        // Verify geo enrichment was applied
        assertNotNull(stored.context?.geo, "geo should be present in stored events")
        assertEquals("TestCity", stored.context?.geo?.city)
        assertEquals("US", stored.context?.geo?.country)

        // Verify the triggered pipeline was applied
        assertNotNull(stored.events.first().element, "pipeline should have enriched the event")
        assertEquals("script-enriched", stored.events.first().element?.id)
    }

    @Test
    fun `full pipeline with multiple events processes all`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()
        val service = createService(repo)

        val events = Events(
            context = EventContext(
                appId = "test-app",
                appVersion = "1.0.0",
                device = Device(
                    installationId = "test-install-id",
                    manufacturer = "Test",
                    model = "TestDevice",
                    platform = "test",
                    primaryLocale = "en-US",
                    systemName = "TestOS",
                    timezone = "UTC",
                    type = "desktop",
                    version = "1.0"
                ),
                sessionId = "test-session-id"
            ),
            events = listOf(
                Event(created = System.currentTimeMillis(), type = EventType.Session, clientId = "1"),
                Event(created = System.currentTimeMillis(), type = EventType.Session, clientId = "2"),
                Event(created = System.currentTimeMillis(), type = EventType.Session, clientId = "3"),
            ),
            sent = System.currentTimeMillis(),
            sentMicros = 0
        )

        service.queue(createTestContext(), events)

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val stored = repo.processedBatches.first()
        assertEquals(3, stored.events.size, "all events should be stored")
        // All session events should have been enriched by the pipeline
        stored.events.forEach { event ->
            assertNotNull(event.element, "each event should have been enriched")
            assertEquals("script-enriched", event.element?.id)
        }
    }

    @Test
    fun `flush delegates to event repository`() = runBlocking {
        val repo = TestEventRepository()
        val service = createService(repo)
        // flush should not throw
        service.flush()
    }

    // ==================== Custom Transform Chain Tests ====================

    @Test
    fun `transforms are applied in order before persistence`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()

        val transform1 = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                return events.copy(
                    context = events.context?.copy(geo = Geo(city = "Transform1City"))
                )
            }
        }

        val transform2 = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                val currentCity = events.context?.geo?.city ?: ""
                return events.copy(
                    context = events.context?.copy(
                        geo = events.context?.geo?.copy(city = "$currentCity+Transform2")
                    )
                )
            }
        }

        val service = createService(repo, listOf(transform1, transform2))
        service.queue(createTestContext(), createTestEvents())

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val stored = repo.processedBatches.first()
        assertEquals("Transform1City+Transform2", stored.context?.geo?.city)
    }

    @Test
    fun `transform failure does not block persistence`() = runBlocking {
        val repo = TestEventRepository()
        val latch = repo.expectBatch()

        val successTransform = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                return events.copy(
                    context = events.context?.copy(geo = Geo(city = "SuccessCity"))
                )
            }
        }

        val failingTransform = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                throw RuntimeException("Transform failure")
            }
        }

        val service = createService(repo, listOf(successTransform, failingTransform))
        service.queue(createTestContext(), createTestEvents())

        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val stored = repo.processedBatches.first()
        assertEquals("SuccessCity", stored.context?.geo?.city)
    }
}
