package bosca.analytics.persistence

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.Device
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventQueueTest {
    @Test
    fun `queued events survive queue recreation until delivery finishes`() = runTest {
        val store = InMemoryAnalyticsEventStore()
        val context = context("session")
        val event = event("event-1")

        EventQueue(store, 100).add("context-1", context, event)
        val restored = EventQueue(store, 100)
        assertEquals(1, restored.size())

        val pending = restored.get() ?: error("queue checkout missing")
        assertEquals("context-1", pending.groups.single().contextId)
        pending.finish(pending.groups.single())
        pending.close()

        assertEquals(0, EventQueue(store, 100).size())
    }

    @Test
    fun `checkout groups structured events by context and is exclusive`() = runTest {
        val store = InMemoryAnalyticsEventStore()
        val queue = EventQueue(store, 100)
        assertEquals(0, queue.size())

        val emptyCheckout = requireNotNull(queue.get())
        assertEquals(emptyList(), emptyCheckout.groups)
        assertNull(queue.get())
        assertFalse(emptyCheckout.close())

        queue.add("context-a", context("session-a"), event("event-a"))
        queue.add("context-b", context("session-b"), event("event-b"))
        val pending = requireNotNull(queue.get())
        assertEquals(2, pending.groups.size)
        queue.add("context-c", context("session-c"), event("event-c"))
        assertTrue(pending.close())

        val all = requireNotNull(queue.get())
        all.groups.forEach { all.finish(it) }
        assertFalse(all.close())
        assertEquals(0, queue.size())
    }

    @Test
    fun `store replaces duplicate event ids without serializing the queue`() = runTest {
        val store = InMemoryAnalyticsEventStore(
            listOf(StoredAnalyticsEvent("first", context("session-a"), event("duplicate"))),
        )
        store.add(StoredAnalyticsEvent("second", context("session-b"), event("duplicate")))
        store.remove(emptySet())

        val stored = store.read(1).single()
        assertEquals("second", stored.contextId)
        assertEquals("session-b", stored.context.sessionId)
        assertEquals("duplicate", stored.events.single().clientId)
        assertEquals(1, store.size())
    }

    @Test
    fun `checkout never reads more than its configured batch`() = runTest {
        val store = InMemoryAnalyticsEventStore(
            (1..5).map { index ->
                StoredAnalyticsEvent("context", context("session"), event("event-$index").copy(created = index.toLong()))
            },
        )
        val queue = EventQueue(store, 2)

        val first = requireNotNull(queue.get())
        assertEquals(2, first.eventCount)
        assertEquals(listOf("event-1", "event-2"), first.groups.single().events.map { it.clientId })
        first.groups.forEach { first.finish(it) }
        assertTrue(first.close())

        val second = requireNotNull(queue.get())
        assertEquals(2, second.eventCount)
        second.groups.forEach { second.finish(it) }
        assertTrue(second.close())
    }

    @Test
    fun `store rejects an unbounded read request`() = runTest {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InMemoryAnalyticsEventStore().read(0)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            EventQueue(InMemoryAnalyticsEventStore(), 0)
        }
    }

    private fun context(sessionId: String) = AnalyticsContext(
        appId = "app",
        appVersion = "1",
        clientId = "client",
        device = Device("iid", "maker", "model", "desktop", "en", "Desktop", "UTC", "desktop", "1"),
        sessionId = sessionId,
    )

    private fun event(clientId: String) = AnalyticsEvent(
        clientId = clientId,
        type = AnalyticsEventType.INTERACTION,
        created = 1,
        createdMicros = 2,
        element = AnalyticsElement("save", "button"),
    )
}
