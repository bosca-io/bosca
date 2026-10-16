@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.transform.fums

import bosca.analytics.service.EventProcessingHealth
import bosca.analytics.jobs.FumsDeliveryJob
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.analytics.service.EventProcessingServiceImpl
import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.service.NatsEventConsumer
import bosca.analytics.model.*
import bosca.bible.providers.service.BibleProviderService
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
import bosca.db.ConnectionPool
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.di.provide
import bosca.di.asProvider
import bosca.analytics.configuration.TransformConfiguration
import bosca.counter.Counter
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.test.resources.SharedValkeyContainer
import bosca.test.resources.SharedNatsContainer
import bosca.server.Headers
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.test.*

/** Uses generated worker wiring, the real queue, and a local FUMS endpoint. */
class FumsAnalyticsIntegrationTest {
    @Test
    fun `NATS forwards Analytics events and delivers FUMS jobs`() = runBlocking { integration(useNats = true, ingestion = true) }

    @Test
    fun `Redis delivery works while Analytics forwards through NATS`() = runBlocking { integration(useNats = false, ingestion = true) }

    @Test
    fun `server configuration can enable FUMS in processing instead of ingestion`() = runBlocking {
        integration(useNats = false, ingestion = false)
    }

    private suspend fun integration(useNats: Boolean, ingestion: Boolean) {
        val redis = SharedValkeyContainer()
        val nats = SharedNatsContainer()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        redis.start()
        nats.start()
        server.start()
        val pool = redis.newConnectionPool(maxConnections = 5)
        val natsPool = nats.newConnectionPool(maxConnections = 5)
        var application: BoscaApplication? = null
        try {
            val json = Json
            val locks = if (useNats) NatsDistributedLockFactory(natsPool) else RedisDistributedLockFactory(pool)
            val manager = RedisCacheManager(pool, RedisCacheScripts(pool), scope)
            val factory: JobQueueFactory = if (useNats) NatsJobQueueFactory(natsPool, json, locks, null, emptyList(), false)
                else RedisJobQueueFactory(pool, json, locks, null, emptyList(), false)
            val bibles = mockk<BibleProviderService>()
            val bibleId = UUID.random()
            val audioId = UUID.random()
            coEvery { bibles.getFumsToken(bibleId, 2, "reader", "JHN.1", null) } returns "private-text-token"
            coEvery { bibles.getFumsToken(bibleId, 2, "reader", "JHN.1", audioId) } returns "private-audio-token"
            ProviderRegistry.clear()
            val app = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))
            application = app
            provides<Json>(overrideExisting = true) { json }
            provides<CacheManager> { manager }
            provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
            provides<ConnectionPool> { mockk(relaxed = true) }
            provides<BibleProviderService> { bibles }
            provides<JobQueueFactory> { factory }
            AnalyticsProviderRegistrar().register()
            val queue = provide<JobQueue>("fums")
            val contentQueue = factory.create("content")
            val analyticsQueue = factory.create("analytics")
            provides<FumsReporter>(overrideExisting = true) { FumsReporter(OkHttpClient(), server.url("/f3")) }
            val context = EventContext("app", "1", device = Device("device", "", "", "", "", "", "", "", ""),
                sessionId = "session", userId = "user")
            fun event(audio: Boolean) = Event(1, type = if (audio) EventType.Interaction else EventType.Impression,
                element = Element(type = if (audio) "bible-audio" else "bible-text", extras = buildJsonObject {
                    put("bible_id", bibleId.toString())
                    put("bible_version", "2")
                    put("bible_variant", "reader")
                    put("bible_usfm", "JHN.1")
                    if (audio) put("bible_audio_id", audioId.toString())
                }))
            val input = Events(context, listOf(event(false), event(true)), 100, 10)
            val configuration = TransformConfiguration()
            suspend fun transforms(enabled: Boolean): EventPipelineTransforms = configuration.transforms(
                configuration.cloudflare(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), json, EventProcessingConfiguration(fumsEnabled = enabled),
                mockk<Counter>(relaxed = true), mockk(relaxed = true), queue.asProvider(),
            )
            val health = EventProcessingHealth()
            val ingest = EventProcessingServiceImpl(app, NatsEventRepository(natsPool, json, health),
                transforms(ingestion), EventProcessingConfiguration(workerCount = 1), health)
            val storage = mockk<EventRepository>()
            coJustRun { storage.process(any()) }
            val consumer = NatsEventConsumer(natsPool, json, storage,
                transforms(!ingestion), EventProcessingConfiguration())
            val (_, subscription) = consumer.initializeJetStream()
            withTimeout(60_000) {
                ingest.processRequest(EventPipelineContext(Headers.Empty), input)
                if (!ingestion) assertNull(queue.dequeue(100.milliseconds))
                val messages = subscription.fetch(2, java.time.Duration.ofSeconds(5))
                assertEquals(2, messages.size)
                for (message in messages) consumer.processMessage(message)
                coVerify(exactly = 2) { storage.process(any()) }
                assertNull(contentQueue.dequeue(100.milliseconds))
                assertNull(analyticsQueue.dequeue(100.milliseconds))
                val first = assertNotNull(queue.dequeue())
                val firstPayload = json.decodeFromJsonElement(FumsDeliveryJob.serializer(), first.getDefinition())
                assertFalse(first.getDefinition().toString().contains("token", ignoreCase = true))
                server.enqueue(MockResponse.Builder().code(503).build())
                val error = assertFailsWith<IOException> { execute(queue, first) }
                queue.markFailed(first, error, true)
                if (!useNats) queue.expireAllJobs()
                server.enqueue(MockResponse.Builder().code(204).build())
                val next = assertNotNull(queue.dequeue())
                execute(queue, next)
                queue.markComplete(next)
                server.enqueue(MockResponse.Builder().code(204).build())
                // NATS retries after 30 seconds; allow time for delivery beyond that boundary.
                val last = assertNotNull(queue.dequeue(40.seconds))
                execute(queue, last)
                queue.markComplete(last)
                assertNull(queue.dequeue(100.milliseconds))
                assertEquals(3, server.requestCount)
                val requests = List(3) { server.takeRequest() }
                val failedToken = if (firstPayload.audioId == null) "private-text-token" else "private-audio-token"
                assertEquals(failedToken, requests.first().url.queryParameter("t"))
                assertEquals(2, requests.count { it.url.queryParameter("t") == failedToken })
                assertEquals(setOf("private-text-token", "private-audio-token"), requests.map { it.url.queryParameter("t") }.toSet())
                for (request in requests) {
                    assertEquals(1, request.url.queryParameterValues("t").size)
                    assertEquals(64, request.url.queryParameter("dId")?.length)
                    assertEquals("session", request.url.queryParameter("sId"))
                    assertEquals(64, request.url.queryParameter("uId")?.length)
                    assertNull(request.headers["api-key"])
                }
                coVerify(exactly = 3) { bibles.getFumsToken(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { bibles.getProvider(any()) }
            }
        } finally {
            application?.shutdown()
            ProviderRegistry.clear()
            scope.cancel()
            pool.close()
            server.close()
            redis.stop()
            nats.stop()
        }
    }

    private suspend fun execute(queue: JobQueue, task: Job) {
        withRequestCache {
            withContext(queue.asCoroutineContext(task)) { task.newExecutor().execute() }
        }
    }
}
