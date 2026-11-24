package bosca.analytics.repository

import bosca.analytics.livesessions.DisabledLiveSessions
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.model.LiveSession
import bosca.analytics.queue.AnalyticsEventQueue
import bosca.analytics.queue.DiskAnalyticsEventQueue
import bosca.analytics.service.EventProcessingHealth
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class QueuedEventRepositoryTest {
    private val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
    @Test fun `durable disk forwarding clears health and storage failure sets it`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("analytics-forwarding")
        val queue = DiskAnalyticsEventQueue(directory, Json, maxSegmentEvents = 1)
        val health = EventProcessingHealth()
        try {
            health.queueFailed()
            val repository = QueuedEventRepository(queue, health)
            repository.process(events); repository.flush()
            assertTrue(health.isReady); assertEquals(1L, queue.status().pending)
            queue.shutdown()
            assertFailsWith<IllegalStateException> { repository.process(events) }
            assertFalse(health.isReady)
        } finally { queue.shutdown(); directory.toFile().deleteRecursively() }
    }
    @Test fun `forwarding cancellation does not become an availability failure`(): Unit = runBlocking {
        val queue = mockk<AnalyticsEventQueue>()
        coEvery { queue.enqueue(any()) } throws CancellationException("cancelled")
        val health = EventProcessingHealth()
        assertFailsWith<CancellationException> { QueuedEventRepository(queue, health).process(events) }
        assertTrue(health.isReady)
        coEvery { queue.enqueue(any()) } throws IOException("unavailable")
        assertFailsWith<IOException> { QueuedEventRepository(queue, health).process(events) }
        assertFalse(health.isReady)
    }
    @Test fun `disabled live sessions do not require a transport`(): Unit = runBlocking {
        val sessions = DisabledLiveSessions()
        sessions.publish("app", LiveSession("session", 1.0, 2.0))
        assertTrue(sessions.subscribe(null, null).toList().isEmpty())
    }
}
