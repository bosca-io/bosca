package yks.lib0

/**
 * Observable with named events, matching lib0/observable ObservableV2.
 * Supports typed event listeners using string event names.
 */
open class Observable {
    private val listeners = mutableMapOf<String, MutableList<(Array<out Any?>) -> Unit>>()
    private val handlerMap = mutableMapOf<Any, (Array<out Any?>) -> Unit>()

    /**
     * Register a listener for the given event name.
     * Returns an unsubscribe function.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> on(eventName: String, handler: (T) -> Unit): () -> Unit {
        val list = listeners.getOrPut(eventName) { mutableListOf() }
        val wrapper: (Array<out Any?>) -> Unit = { args ->
            handler(args[0] as T)
        }
        list.add(wrapper)
        handlerMap[handler as Any] = wrapper
        return { off(eventName, handler) }
    }

    /**
     * Register a listener that receives two arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T1, T2> on2(eventName: String, handler: (T1, T2) -> Unit): () -> Unit {
        val list = listeners.getOrPut(eventName) { mutableListOf() }
        val wrapper: (Array<out Any?>) -> Unit = { args ->
            handler(args[0] as T1, args[1] as T2)
        }
        list.add(wrapper)
        handlerMap[handler as Any] = wrapper
        return { off2(eventName, handler) }
    }

    /** Emit an event with one argument. */
    fun emit(eventName: String, value: Any?) {
        val list = listeners[eventName] ?: return
        for (handler in list.toList()) {
            handler(arrayOf(value))
        }
    }

    /** Emit an event with two arguments. */
    fun emit2(eventName: String, arg1: Any?, arg2: Any?) {
        val list = listeners[eventName] ?: return
        for (handler in list.toList()) {
            handler(arrayOf(arg1, arg2))
        }
    }

    /** Emit an event with three arguments. */
    fun emit3(eventName: String, arg1: Any?, arg2: Any?, arg3: Any?) {
        val list = listeners[eventName] ?: return
        for (handler in list.toList()) {
            handler(arrayOf(arg1, arg2, arg3))
        }
    }

    /** Remove a listener. */
    @Suppress("UNCHECKED_CAST")
    fun <T> off(eventName: String, handler: (T) -> Unit) {
        val wrapper = handlerMap.remove(handler as Any) ?: return
        listeners[eventName]?.remove(wrapper)
    }

    /** Remove a two-arg listener. */
    @Suppress("UNCHECKED_CAST")
    fun <T1, T2> off2(eventName: String, handler: (T1, T2) -> Unit) {
        val wrapper = handlerMap.remove(handler as Any) ?: return
        listeners[eventName]?.remove(wrapper)
    }

    /** Check if there are any listeners for the given event. */
    fun hasListeners(eventName: String): Boolean {
        return listeners[eventName]?.isNotEmpty() == true
    }

    /** Destroy this observable and clear all listeners. */
    open fun destroy() {
        listeners.clear()
        handlerMap.clear()
    }
}
