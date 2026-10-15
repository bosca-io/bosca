@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.EventProcessingHealth
import bosca.analytics.service.EventProcessingService
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            var processed = CompletableDeferred<Unit>()
            val errors = mockk<ErrorGroupService>()
            coEvery { errors.recordBatch(any()) } coAnswers { processed.complete(Unit) }
            provides<ErrorGroupService>(overrideExisting = true) { errors }
            application.configureCollectorHealth()

            try {
                val service = provide<EventProcessingService>()
                val context = EventPipelineContext(Headers.Empty)
                val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
                assertEquals(200, status(application, "ready"))
                service.queue(context, events)
                withTimeout(15_000) { processed.await() }
                processed = CompletableDeferred()

                server.dockerClient.pauseContainerCmd(server.containerId).exec()
                try {
                    service.queue(context, events.copy(sent = 2))
                    // The composite still runs the error sink after the NATS publish fails.
                    withTimeout(15_000) { processed.await() }
                    withTimeout(5_000) { while (health.isReady) delay(10) }
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
            } finally {
                application.shutdown()
                nats.close()
            }
        }
    }
}
