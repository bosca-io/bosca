package bosca.events

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventTest {

    @Test
    fun enabledEventManagerFilterIsObject() {
        assertNotNull(EnabledEventManagerFilter)
    }

    @Test
    fun enabledFilterAlwaysReturnsTrue() {
        val event = object : Event {}
        assertTrue(EnabledEventManagerFilter.isEnabled(event))
    }

    @Test
    fun disabledEventManagerFilterIsObject() {
        assertNotNull(DisabledEventManagerFilter)
    }

    @Test
    fun disabledFilterAlwaysReturnsFalse() {
        val event = object : Event {}
        assertFalse(DisabledEventManagerFilter.isEnabled(event))
    }

    @Test
    fun eventManagerDefaultFilterIsEnabled() {
        val manager = EventManager()
        val event = object : Event {}
        assertTrue(manager.isEnabled(event))
    }

    @Test
    fun missingEventManagerIsObject() {
        assertNotNull(MissingEventManager)
    }

    @Test
    fun eventManagerDisabledBlock() {
        val manager = EventManager()
        val event = object : Event {}
        manager.disabled {
            assertFalse(manager.isEnabled(event))
        }
        assertTrue(manager.isEnabled(event))
    }
}
