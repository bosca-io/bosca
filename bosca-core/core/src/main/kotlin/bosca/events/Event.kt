package bosca.events

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Marker interface for all domain events that can be dispatched through the [EventManager].
 *
 * Implementations represent specific occurrences in the system (e.g., content created,
 * workflow state changed) and can be selectively enabled or disabled via [EventManagerFilter].
 *
 * Events that may be dispatched inside a [deferredEvents] scope should override [identityKey]
 * so that duplicate events (e.g., three `MetadataUpdated` calls for the same item during a
 * batch of flag-setting operations) collapse into a single dispatch when the scope exits.
 */
interface Event {

    /**
     * Returns a value that uniquely identifies this event for deduplication purposes.
     * Two events of the same class with equal identity keys are considered duplicates
     * inside a [deferredEvents] scope — only the last one is dispatched.
     *
     * The default returns [System.identityHashCode], meaning no deduplication across
     * instances. Override this in event types that carry a natural key (e.g., entity ID).
     */
    fun identityKey(): Any = System.identityHashCode(this)
}

suspend fun Event.dispatch() {
    error("not implemented")
}

/**
 * Controls whether specific [Event] types should be processed by the [EventManager].
 *
 * Implementations can selectively suppress events based on type, context, or other criteria.
 * The [EventManager] consults this filter before dispatching each event.
 *
 * @see EnabledEventManagerFilter
 * @see DisabledEventManagerFilter
 */
interface EventManagerFilter {

    /**
     * Determines whether the given [event] should be dispatched.
     *
     * @param event the event to evaluate
     * @return `true` if the event should be processed, `false` to suppress it
     */
    fun isEnabled(event: Event): Boolean
}

object EnabledEventManagerFilter : EventManagerFilter {
    override fun isEnabled(event: Event): Boolean = true
}

object DisabledEventManagerFilter : EventManagerFilter {
    override fun isEnabled(event: Event): Boolean = false
}

/**
 * Captures events instead of dispatching them immediately. When the scope exits,
 * deduplicated events are flushed via their normal [Event.dispatch] path.
 *
 * Deduplication uses `(event::class, event.identityKey())` — events of the same
 * type and identity key are collapsed, keeping only the last occurrence.
 *
 * @see deferredEvents
 */
class DeferredEventManagerFilter : EventManagerFilter {

    private val callbacks = linkedMapOf<Pair<KClass<*>, Any>, suspend () -> Unit>()

    override fun isEnabled(event: Event): Boolean = false

    /**
     * Registers a deferred dispatch callback, deduplicating by event class and [Event.identityKey].
     * When multiple events share the same key, only the last callback is kept.
     */
    fun deferDispatch(event: Event, dispatch: suspend () -> Unit) {
        callbacks[event::class to event.identityKey()] = dispatch
    }

    /**
     * Returns the deduplicated dispatch callbacks in insertion order.
     */
    fun deferredCallbacks(): List<suspend () -> Unit> = callbacks.values.toList()
}

/**
 * Defers event dispatch within [block], then flushes all unique events when the
 * scope exits. Events of the same type and [Event.identityKey] are deduplicated
 * so that batch operations (e.g., setting multiple public flags on the same item)
 * produce a single downstream dispatch instead of one per mutation.
 */
suspend fun <T> deferredEvents(block: suspend () -> T): T {
    val manager = eventManager()
    val filter = DeferredEventManagerFilter()
    val savedFilter = manager.filter
    manager.filter = filter
    try {
        return block()
    } finally {
        manager.filter = savedFilter
        for (callback in filter.deferredCallbacks()) {
            try {
                callback()
            } catch (e: Exception) {
                deferredEventsLog.warn("deferred event dispatch failed: {}", e.message, e)
            }
        }
    }
}

private val deferredEventsLog = org.slf4j.LoggerFactory.getLogger("bosca.events.DeferredEvents")

open class EventManager {

    open var filter: EventManagerFilter = EnabledEventManagerFilter

    fun isEnabled(event: Event): Boolean {
        return filter.isEnabled(event)
    }

    fun deferDispatch(event: Event, dispatch: suspend () -> Unit) {
        val f = filter
        if (f is DeferredEventManagerFilter) {
            f.deferDispatch(event, dispatch)
        }
    }

    inline fun <T> disabled(block: () -> T): T {
        return filtered(DisabledEventManagerFilter, block)
    }

    inline fun <T> filtered(filter: EventManagerFilter, block: () -> T): T {
        val originalFilter = this.filter
        try {
            this.filter = filter
            return block()
        } finally {
            this.filter = originalFilter
        }
    }
}

object MissingEventManager : EventManager() {

    override var filter: EventManagerFilter
        get() = super.filter
        set(value) {}
}

fun EventManager.asCoroutineContext(): CoroutineContext = EventManagerContext(this)

private class EventManagerContext(val manager: EventManager) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<EventManagerContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend fun eventManager(): EventManager = currentCoroutineContext().eventManager()

fun CoroutineContext.eventManager(): EventManager {
    return this[EventManagerContext.Key]?.manager ?: MissingEventManager
}

suspend fun <T> withEventManager(block: suspend () -> T): T {
    val manager = EventManager()
    return withContext(manager.asCoroutineContext()) {
        block()
    }
}