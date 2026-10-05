package bosca.analytics.persistence

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates exclusive, bounded delivery checkouts over a structured event store. */
internal class EventQueue(
    private val store: AnalyticsEventStore,
    private val batchSize: Int,
) {
    private val mutex = Mutex()
    private var checkedOut = false
    private var generation = 0L

    init {
        require(batchSize > 0) { "Event batch size must be positive" }
    }

    suspend fun add(contextId: String, context: AnalyticsContext, event: AnalyticsEvent) {
        mutex.withLock {
            store.add(StoredAnalyticsEvent(contextId, context, event))
            generation++
        }
    }

    suspend fun size(): Int = mutex.withLock { store.size() }

    suspend fun get(): PendingEvents? = mutex.withLock {
        if (checkedOut) return@withLock null
        val groups = store.read(batchSize)
        checkedOut = true
        PendingEvents(
            generation = generation,
            eventCount = groups.sumOf { it.events.size },
            groups = groups,
            queue = this,
        )
    }

    suspend fun finish(group: ContextEvents) {
        val ids = group.events.mapTo(mutableSetOf()) { it.clientId }
        mutex.withLock {
            store.remove(ids)
        }
    }

    suspend fun close(snapshotGeneration: Long): Boolean = mutex.withLock {
        checkedOut = false
        generation != snapshotGeneration || store.size() > 0
    }
}
