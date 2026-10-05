package yks

import yks.lib0.*
import kotlin.test.*

/**
 * Tests for lib0 EventHandler and Observable covering uncovered branches.
 */
class Lib0Test {

    // ---------------------------------------------------------------
    // EventHandler
    // ---------------------------------------------------------------

    @Test
    fun testEventHandlerAddAndCallListener() {
        val handler = EventHandler<String>()
        var received: String? = null
        handler.addListener { received = it }
        handler.callListeners("hello")
        assertEquals("hello", received)
    }

    @Test
    fun testEventHandlerRemoveListener() {
        val handler = EventHandler<Int>()
        var callCount = 0
        val listener: (Int) -> Unit = { callCount++ }
        handler.addListener(listener)

        handler.callListeners(1)
        assertEquals(1, callCount)

        handler.removeListener(listener)

        handler.callListeners(2)
        assertEquals(1, callCount) // should not increment
    }

    @Test
    fun testEventHandlerRemoveViaUnsubscribe() {
        val handler = EventHandler<Int>()
        var callCount = 0
        val unsubscribe = handler.addListener { callCount++ }

        handler.callListeners(1)
        assertEquals(1, callCount)

        unsubscribe()

        handler.callListeners(2)
        assertEquals(1, callCount)
    }

    @Test
    fun testEventHandlerCallWithNoListeners() {
        val handler = EventHandler<String>()
        // Should not throw
        handler.callListeners("test")
        assertFalse(handler.hasListeners())
    }

    @Test
    fun testEventHandlerHasListeners() {
        val handler = EventHandler<Int>()
        assertFalse(handler.hasListeners())

        val unsub = handler.addListener {}
        assertTrue(handler.hasListeners())

        unsub()
        assertFalse(handler.hasListeners())
    }

    @Test
    fun testEventHandlerDestroy() {
        val handler = EventHandler<Int>()
        handler.addListener {}
        handler.addListener {}
        assertTrue(handler.hasListeners())

        handler.destroy()
        assertFalse(handler.hasListeners())

        // After destroy, calling listeners should not throw
        handler.callListeners(1)
    }

    @Test
    fun testEventHandlerMultipleListeners() {
        val handler = EventHandler<Int>()
        val values = mutableListOf<Int>()
        handler.addListener { values.add(it * 1) }
        handler.addListener { values.add(it * 10) }
        handler.addListener { values.add(it * 100) }

        handler.callListeners(5)
        assertEquals(listOf(5, 50, 500), values)
    }

    @Test
    fun testEventHandlerRemoveNonexistentListener() {
        val handler = EventHandler<Int>()
        val listener: (Int) -> Unit = {}
        // Should not throw when removing a listener that was never added
        handler.removeListener(listener)
    }

    // ---------------------------------------------------------------
    // Observable
    // ---------------------------------------------------------------

    @Test
    fun testObservableOnAndEmit() {
        val obs = Observable()
        var received: String? = null
        obs.on<String>("test") { received = it }
        obs.emit("test", "hello")
        assertEquals("hello", received)
    }

    @Test
    fun testObservableOff() {
        val obs = Observable()
        var callCount = 0
        val handler: (Int) -> Unit = { callCount++ }
        obs.on("event", handler)

        obs.emit("event", 1)
        assertEquals(1, callCount)

        obs.off("event", handler)

        obs.emit("event", 2)
        assertEquals(1, callCount) // should not increment
    }

    @Test
    fun testObservableOn2AndEmit2() {
        val obs = Observable()
        var arg1: String? = null
        var arg2: Int? = null
        obs.on2<String, Int>("test") { a, b ->
            arg1 = a
            arg2 = b
        }
        obs.emit2("test", "hello", 42)
        assertEquals("hello", arg1)
        assertEquals(42, arg2)
    }

    @Test
    fun testObservableOff2() {
        val obs = Observable()
        var callCount = 0
        val handler: (String, Int) -> Unit = { _, _ -> callCount++ }
        obs.on2("event", handler)

        obs.emit2("event", "a", 1)
        assertEquals(1, callCount)

        obs.off2("event", handler)

        obs.emit2("event", "b", 2)
        assertEquals(1, callCount)
    }

    @Test
    fun testObservableEmit3() {
        val obs = Observable()
        var received = mutableListOf<Any?>()
        obs.on<Any?>("test") { received.add(it) }
        // emit3 sends 3 args but our handler only gets the first
        obs.emit3("test", "a", "b", "c")
        assertEquals(1, received.size)
        assertEquals("a", received[0])
    }

    @Test
    fun testObservableReturnUnsubscribe() {
        val obs = Observable()
        var callCount = 0
        val unsub = obs.on<Int>("event") { callCount++ }

        obs.emit("event", 1)
        assertEquals(1, callCount)

        unsub()

        obs.emit("event", 2)
        assertEquals(1, callCount)
    }

    @Test
    fun testObservableReturnUnsubscribeOn2() {
        val obs = Observable()
        var callCount = 0
        val unsub = obs.on2<Int, String>("event") { _, _ -> callCount++ }

        obs.emit2("event", 1, "a")
        assertEquals(1, callCount)

        unsub()

        obs.emit2("event", 2, "b")
        assertEquals(1, callCount)
    }

    @Test
    fun testObservableHasListeners() {
        val obs = Observable()
        assertFalse(obs.hasListeners("event"))

        val unsub = obs.on<Int>("event") {}
        assertTrue(obs.hasListeners("event"))
        assertFalse(obs.hasListeners("other"))

        unsub()
        assertFalse(obs.hasListeners("event"))
    }

    @Test
    fun testObservableDestroy() {
        val obs = Observable()
        obs.on<Int>("a") {}
        obs.on<String>("b") {}
        assertTrue(obs.hasListeners("a"))
        assertTrue(obs.hasListeners("b"))

        obs.destroy()
        assertFalse(obs.hasListeners("a"))
        assertFalse(obs.hasListeners("b"))
    }

    @Test
    fun testObservableEmitNoListeners() {
        val obs = Observable()
        // Should not throw when emitting without listeners
        obs.emit("unknown", "value")
        obs.emit2("unknown", "a", "b")
        obs.emit3("unknown", "a", "b", "c")
    }

    @Test
    fun testObservableMultipleListenersSameEvent() {
        val obs = Observable()
        val values = mutableListOf<Int>()
        obs.on<Int>("event") { values.add(it) }
        obs.on<Int>("event") { values.add(it * 10) }

        obs.emit("event", 3)
        assertEquals(listOf(3, 30), values)
    }

    @Test
    fun testObservableOffNonexistentHandler() {
        val obs = Observable()
        val handler: (Int) -> Unit = {}
        // Should not throw
        obs.off("event", handler)
    }
}
