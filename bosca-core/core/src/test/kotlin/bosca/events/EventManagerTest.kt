package bosca.events

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventManagerTest {

    private class TestEvent : Event

    @Test
    fun `EnabledEventManagerFilter always returns true`() {
        assertTrue(EnabledEventManagerFilter.isEnabled(TestEvent()))
    }

    @Test
    fun `DisabledEventManagerFilter always returns false`() {
        assertFalse(DisabledEventManagerFilter.isEnabled(TestEvent()))
    }

    @Test
    fun `EventManager defaults to enabled filter`() {
        val manager = EventManager()
        assertTrue(manager.isEnabled(TestEvent()))
    }

    @Test
    fun `EventManager disabled block suppresses events`() {
        val manager = EventManager()
        manager.disabled {
            assertFalse(manager.isEnabled(TestEvent()))
        }
        assertTrue(manager.isEnabled(TestEvent()))
    }

    @Test
    fun `EventManager filtered block uses custom filter and restores original`() {
        val manager = EventManager()
        val customFilter = object : EventManagerFilter {
            override fun isEnabled(event: Event): Boolean = false
        }
        manager.filtered(customFilter) {
            assertFalse(manager.isEnabled(TestEvent()))
        }
        assertTrue(manager.isEnabled(TestEvent()))
    }

    @Test
    fun `MissingEventManager ignores filter assignment`() {
        MissingEventManager.filter = DisabledEventManagerFilter
        assertTrue(MissingEventManager.isEnabled(TestEvent()))
    }

    @Test
    fun `EventManager coroutine context round-trip`() = runBlocking {
        val manager = EventManager()
        withContext(manager.asCoroutineContext()) {
            val retrieved = eventManager()
            assertEquals(manager, retrieved)
        }
    }

    @Test
    fun `eventManager returns MissingEventManager when no context`() = runBlocking {
        val retrieved = eventManager()
        assertEquals(MissingEventManager, retrieved)
    }

    @Test
    fun `withEventManager provides an EventManager in context`() = runBlocking {
        withEventManager {
            val manager = eventManager()
            assertTrue(manager.isEnabled(TestEvent()))
        }
    }

    @Test
    fun `DeferredEventManagerFilter isEnabled always returns false`() {
        val filter = DeferredEventManagerFilter()
        assertFalse(filter.isEnabled(TestEvent()))
    }

    @Test
    fun `DeferredEventManagerFilter deduplicates callbacks by identity key`() {
        val filter = DeferredEventManagerFilter()
        val calls = mutableListOf<String>()
        filter.deferDispatch(KeyedEvent("a")) { calls.add("first") }
        filter.deferDispatch(KeyedEvent("a")) { calls.add("second") }
        assertEquals(1, filter.deferredCallbacks().size)
    }

    @Test
    fun `deferDispatch is a no-op when filter is not deferred`() {
        val manager = EventManager()
        val calls = mutableListOf<String>()
        manager.deferDispatch(TestEvent()) { calls.add("called") }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `deferredEvents replays deferred dispatch callbacks`() = runBlocking {
        val calls = mutableListOf<String>()
        withEventManager {
            deferredEvents {
                val mgr = eventManager()
                mgr.deferDispatch(KeyedEvent("a")) { calls.add("a") }
                mgr.deferDispatch(KeyedEvent("b")) { calls.add("b") }
                assertTrue(calls.isEmpty())
            }
        }
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun `deferredEvents deduplicates by identity key keeping last callback`() = runBlocking {
        val calls = mutableListOf<String>()
        withEventManager {
            deferredEvents {
                val mgr = eventManager()
                mgr.deferDispatch(KeyedEvent("x")) { calls.add("first") }
                mgr.deferDispatch(KeyedEvent("x")) { calls.add("second") }
            }
        }
        assertEquals(listOf("second"), calls)
    }

    private class KeyedEvent(private val key: String) : Event {
        override fun identityKey(): Any = key
    }
}
