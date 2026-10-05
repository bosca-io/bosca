package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.service.EventProcessingService
import bosca.server.Headers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InProcessServerAnalyticsClientTest {

    private val eventProcessingService = mockk<EventProcessingService>(relaxed = true)
    private val client = InProcessServerAnalyticsClient(
        eventProcessingService = eventProcessingService,
        appId = "test-app",
        contextSupplier = { EventPipelineContext(Headers.Empty) },
    )

    @Test
    fun `capture single event wraps it in an Events batch and queues it`() = runTest {
        val captured = slot<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit
        val event = Event(created = 100L, type = EventType.Session)

        client.capture(event)

        assertEquals(listOf(event), captured.captured.events)
        assertNotNull(captured.captured.sent)
    }

    @Test
    fun `capture pre-built batch passes through unchanged`() = runTest {
        val batch = Events(
            events = listOf(Event(created = 1L, type = EventType.Session)),
            sent = 0L,
            sentMicros = 0L,
        )
        client.capture(batch)
        coVerify { eventProcessingService.queue(any(), batch) }
    }

    @Test
    fun `capture for subject preserves user and installation context`() = runTest {
        val captured = slot<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit
        val device = Device(
            installationId = "stale-installation",
            manufacturer = "Acme",
            model = "Browser",
            platform = "web",
            primaryLocale = "en-US",
            systemName = "web",
            timezone = "UTC",
            type = "web",
            version = "1",
        )

        client.captureForSubject(
            Event(created = 100L, type = EventType.Assignment),
            userId = "principal-1",
            installationId = "installation-1",
            device = device,
        )

        assertEquals("test-app", captured.captured.context?.appId)
        assertEquals("principal-1", captured.captured.context?.userId)
        assertEquals("installation-1", captured.captured.context?.device?.installationId)
        assertEquals("Acme", captured.captured.context?.device?.manufacturer)
    }

    @Test
    fun `capture for anonymous subject creates unknown device context when snapshot is absent`() = runTest {
        val captured = slot<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit

        client.captureForSubject(
            Event(created = 100L, type = EventType.Assignment),
            userId = null,
            installationId = "installation-1",
            device = null,
        )

        assertEquals("installation-1", captured.captured.context?.device?.installationId)
        assertEquals("unknown", captured.captured.context?.device?.type)
    }

    @Test
    fun `capture for subject preserves a device whose installation already matches`() = runTest {
        val captured = slot<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit
        val device = Device(
            installationId = "installation-1",
            manufacturer = "Acme",
            model = "Browser",
            platform = "web",
            primaryLocale = "en-US",
            systemName = "web",
            timezone = "UTC",
            type = "web",
            version = "1",
        )

        client.captureForSubject(
            Event(created = 100L, type = EventType.Assignment),
            userId = "principal-1",
            installationId = "installation-1",
            device = device,
        )

        assertEquals(device, captured.captured.context?.device)

        client.captureForSubject(
            Event(created = 101L, type = EventType.Assignment),
            userId = "principal-1",
            installationId = null,
            device = device,
        )

        assertEquals(device, captured.captured.context?.device)
    }

    @Test
    fun `captureException builds an error event from a throwable`() = runTest {
        val captured = slot<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit
        client.captureException(IllegalStateException("boom"), fatal = true)
        val event = captured.captured.events.single()
        assertEquals(EventType.Error, event.type)
        assertEquals("java.lang.IllegalStateException", event.error?.type)
        assertEquals("boom", event.error?.message)
        assertEquals(true, event.error?.fatal)
    }

    @Test
    fun `capture activates the recursion guard around the pipeline call`() = runTest {
        var sawGuardInsidePipeline = false
        coEvery { eventProcessingService.queue(any(), any<Events>()) } coAnswers {
            sawGuardInsidePipeline = analyticsCaptureGuardActive()
        }
        client.capture(Event(created = 100L, type = EventType.Session))
        assertEquals(true, sawGuardInsidePipeline)
    }

    @Test
    fun `recursive captures from inside the pipeline are dropped`() = runTest {
        // Simulate a transitive failure where a pipeline transform itself
        // tries to emit an event back into the client. The reentrant call
        // is dropped because the guard is already active.
        val outerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { eventProcessingService.queue(any(), any<Events>()) } coAnswers {
            // Re-entrant call from inside the pipeline.
            client.capture(Event(created = 200L, type = EventType.Error))
            outerCalls.incrementAndGet()
        }
        client.capture(Event(created = 100L, type = EventType.Session))
        // queue() was called exactly once for the outer dispatch; the
        // inner reentrant capture must NOT have hit queue() again.
        coVerify(exactly = 1) { eventProcessingService.queue(any(), any<Events>()) }
        assertEquals(1, outerCalls.get())
    }

    @Test
    fun `capture swallows pipeline failures`() = runTest {
        coEvery { eventProcessingService.queue(any(), any<Events>()) } throws RuntimeException("kaboom")
        // Must not throw
        client.capture(Event(created = 100L, type = EventType.Session))
    }

    @Test
    fun `flush delegates to the service and swallows failures`() = runTest {
        coEvery { eventProcessingService.flush() } throws RuntimeException("io error")
        client.flush() // must not throw
        coVerify { eventProcessingService.flush() }
    }

    @Test
    fun `captureException includes each supplied identity in error context`() = runTest {
        val captured = mutableListOf<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit

        client.captureException(RuntimeException("app"), false, appId = "app-1")
        client.captureException(RuntimeException("session"), false, sessionId = "session-2")
        client.captureException(RuntimeException("user"), false, userId = "user-3")

        assertTrue(captured[0].events.single().error?.contextJson.orEmpty().contains("app-1"))
        assertTrue(captured[1].events.single().error?.contextJson.orEmpty().contains("session-2"))
        assertTrue(captured[2].events.single().error?.contextJson.orEmpty().contains("user-3"))
    }

    @Test
    fun `captureException includes ambient session and respects explicit overrides`() = runTest {
        val captured = mutableListOf<Events>()
        coEvery { eventProcessingService.queue(any(), capture(captured)) } returns Unit
        withAnalyticsContext(AnalyticsContext(sessionId = "ambient-session", extras = mapOf("jobId" to "job"))) {
            client.captureException(RuntimeException("ambient"))
            client.captureException(RuntimeException("explicit"), sessionId = "explicit-session")
        }
        val ambient = requireNotNull(captured[0].events.single().error?.contextJson)
        val explicit = requireNotNull(captured[1].events.single().error?.contextJson)
        assertTrue(ambient.contains("ambient-session"))
        assertTrue(ambient.contains("jobId"))
        assertTrue(explicit.contains("explicit-session"))
        kotlin.test.assertFalse(explicit.contains("ambient-session"))
    }

    @Test
    fun `capture preserves cancellation from the processing service`() = runTest {
        coEvery { eventProcessingService.queue(any(), any<Events>()) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> {
            client.capture(Event(created = 100L, type = EventType.Session))
        }
    }

    @Test
    fun `flush succeeds and preserves cancellation`() = runTest {
        client.flush()
        coVerify(exactly = 1) { eventProcessingService.flush() }

        coEvery { eventProcessingService.flush() } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { client.flush() }
    }
}
