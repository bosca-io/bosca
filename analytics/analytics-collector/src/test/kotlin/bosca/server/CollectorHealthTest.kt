@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.Device
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.EventProcessingHealth
import bosca.analytics.service.EventProcessingService
import bosca.analytics.routes.AddEvent
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import bosca.server.config.ApplicationConfig
import bosca.server.routing.RoutingContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class CollectorHealthTest {
    @BeforeTest
    fun setup() = ProviderRegistry.clear()

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private suspend fun status(application: BoscaApplication, path: String): Int {
        val status = slot<HttpStatusCode>()
        val response = mockk<ServerResponse>(relaxed = true)
        every { response.respondText(any(), ContentType.Application.Json, capture(status)) } returns Unit
        val call = ServerCall(mockk<ServerRequest>(relaxed = true), response, Parameters.Empty, application)
        requireNotNull(application.router.resolve(HttpMethod.Get, "/api/v1/collector/$path"))
            .handler(RoutingContext(call, application))
        return status.captured.value
    }

    private suspend fun ingest(application: BoscaApplication, service: EventProcessingService, events: Events): Int {
        val status = slot<HttpStatusCode>()
        val response = mockk<ServerResponse>(relaxed = true)
        every { response.status(capture(status)) } returns Unit
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.path } returns "/api/v1/events"
        every { request.contentType() } returns null
        coEvery { request.bodyText() } returns application.json.encodeToString(Events.serializer(), events)
        val call = ServerCall(request, response, Parameters.Empty, application)
        AddEvent(service).execute(call, mockk())
        return status.captured.value
    }

    @Test
    fun `processing failure removes readiness immediately and liveness after sixty seconds`() = runBlocking {
        var now = 0L
        val health = EventProcessingHealth { now }
        provides<EventProcessingHealth> { health }
        val application = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))
        application.configureCollectorHealth()

        assertEquals(200, status(application, "ready"))
        assertEquals(200, status(application, "live"))
        health.processingFailed()
        assertEquals(503, status(application, "ready"))
        now = 59.seconds.inWholeNanoseconds
        assertEquals(200, status(application, "live"))
        now = 60.seconds.inWholeNanoseconds
        assertEquals(503, status(application, "live"))
        health.processingRecovered()
        assertEquals(200, status(application, "ready"))
        assertEquals(200, status(application, "live"))
    }

    @Test
    fun `generated collector pipeline detects NATS outage and probes recover without incoming events`() = runBlocking {
        GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
            server.start()
            val nats = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
            val application = BoscaApplication(ApplicationConfig.load("eventRepository:\n  type: nats".byteInputStream()))
            var now = 0L
            val health = EventProcessingHealth { now }
            AnalyticsProviderRegistrar().register()
            provides<NatsConnectionPool> { nats }
            provides<EventProcessingHealth>(overrideExisting = true) { health }
            provides<EventPipelineTransforms>(overrideExisting = true) { EventPipelineTransforms(emptyList()) }
            provides<EventProcessingConfiguration>(overrideExisting = true) { EventProcessingConfiguration(workerCount = 1) }
            provides<CacheManager> { mockk(relaxed = true) }
            provides<RequestCacheSerializer> { mockk(relaxed = true) }
            val pool = mockk<ConnectionPool>()
            every { pool.connection() } answers { ConnectionManager(pool) }
            provides<ConnectionPool> { pool }
            val errors = mockk<ErrorGroupService>()
            coEvery { errors.recordBatch(any()) } returns Unit
            provides<ErrorGroupService>(overrideExisting = true) { errors }
            application.configureCollectorHealth()

            try {
                val service = provide<EventProcessingService>()
                val context = EventContext(appId = "app", appVersion = "1", sessionId = "session", userId = "user",
                    device = Device("installation", "test", "test", "JVM", "en", "test", "UTC", "server", "1"))
                val events = Events(context = context, events = listOf(Event(1, type = EventType.Session, clientId = "first")), sent = 1, sentMicros = 0)
                val retry = events.copy(events = listOf(events.events.single().copy(clientId = "second")), sent = 2)
                assertEquals(200, status(application, "ready"))
                assertEquals(202, ingest(application, service, events))
                val connection = nats.systemConnection()
                assertEquals(1L, connection.jetStreamManagement().getStreamInfo(NatsEventRepository.STREAM_NAME).streamState.msgCount)

                server.dockerClient.pauseContainerCmd(server.containerId).exec()
                try {
                    assertEquals(503, ingest(application, service, retry))
                    assertEquals(503, status(application, "ready"))
                    now = 59.seconds.inWholeNanoseconds
                    assertEquals(200, status(application, "live"))
                    now = 60.seconds.inWholeNanoseconds
                    assertEquals(503, status(application, "live"))
                } finally {
                    server.dockerClient.unpauseContainerCmd(server.containerId).exec()
                }

                nats.systemConnection().flush(Duration.ofSeconds(10))
                assertEquals(200, status(application, "ready"))
                assertEquals(200, status(application, "live"))
                assertEquals(202, ingest(application, service, retry.copy(sent = 3)))
                assertEquals(202, ingest(application, service, retry.copy(sent = 4)))
                assertEquals(2L, connection.jetStreamManagement().getStreamInfo(NatsEventRepository.STREAM_NAME).streamState.msgCount)
            } finally {
                application.shutdown()
                nats.close()
            }
        }
    }
}
