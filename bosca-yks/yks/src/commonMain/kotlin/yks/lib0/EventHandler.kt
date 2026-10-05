package yks.lib0

/**
 * Simple event handler matching lib0/observable EventHandler.
 * Holds a list of callback functions.
 */
class EventHandler<T> {
    private val listeners = mutableListOf<(T) -> Unit>()

    fun addListener(listener: (T) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun removeListener(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    fun callListeners(value: T) {
        // Iterate a copy to allow listener removal during callback
        for (listener in listeners.toList()) {
            listener(value)
        }
    }

    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun destroy() {
        listeners.clear()
    }
}
