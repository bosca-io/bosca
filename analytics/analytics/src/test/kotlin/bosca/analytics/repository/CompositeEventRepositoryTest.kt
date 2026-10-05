package bosca.analytics.repository

import bosca.analytics.model.Events
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeEventRepositoryTest {

    private class TrackingRepository : EventRepository {
        var processCount = 0
        var flushCount = 0
        var shouldThrow = false

        override suspend fun process(events: Events) {
            processCount++
            if (shouldThrow) error("simulated failure")
        }

        override suspend fun flush() {
            flushCount++
            if (shouldThrow) error("simulated failure")
        }
    }

    private val emptyEvents = Events(context = null, events = emptyList(), sent = 0L, sentMicros = 0L)

    @Test
    fun `process delegates to all repositories`() = runTest {
        val repo1 = TrackingRepository()
        val repo2 = TrackingRepository()
        val composite = CompositeEventRepository(listOf(repo1, repo2))
        composite.process(emptyEvents)
        assertEquals(1, repo1.processCount)
        assertEquals(1, repo2.processCount)
    }

    @Test
    fun `process isolates sink failures so subsequent sinks still run`() = runTest {
        val failing = TrackingRepository().apply { shouldThrow = true }
        val healthy = TrackingRepository()
        val composite = CompositeEventRepository(listOf(failing, healthy))
        composite.process(emptyEvents)
        assertEquals(1, failing.processCount)
        assertEquals(1, healthy.processCount)
    }

    @Test
    fun `flush isolates sink failures so subsequent sinks still flush`() = runTest {
        val failing = TrackingRepository().apply { shouldThrow = true }
        val healthy = TrackingRepository()
        val composite = CompositeEventRepository(listOf(failing, healthy))
        composite.flush()
        assertEquals(1, failing.flushCount)
        assertEquals(1, healthy.flushCount)
    }

    @Test
    fun `process with no repositories is a no-op`() = runTest {
        val composite = CompositeEventRepository(emptyList())
        composite.process(emptyEvents)
    }

    @Test
    fun `all sinks receive the same events reference`() = runTest {
        val received = mutableListOf<Events>()
        val repo1 = object : EventRepository {
            override suspend fun process(events: Events) { received.add(events) }
            override suspend fun flush() {}
        }
        val repo2 = object : EventRepository {
            override suspend fun process(events: Events) { received.add(events) }
            override suspend fun flush() {}
        }
        val composite = CompositeEventRepository(listOf(repo1, repo2))
        composite.process(emptyEvents)
        assertEquals(2, received.size)
        assertTrue(received[0] === received[1])
    }
}
