package bosca.analytics.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local structured store for tests and explicitly ephemeral clients. */
class InMemoryAnalyticsEventStore(
    initialEvents: List<StoredAnalyticsEvent> = emptyList(),
) : AnalyticsEventStore {
    private val mutex = Mutex()
    private val events = initialEvents.toMutableList()

    override suspend fun add(event: StoredAnalyticsEvent) {
        mutex.withLock {
            events.removeAll { it.event.clientId == event.event.clientId }
            events += event
        }
    }

    override suspend fun read(limit: Int): List<ContextEvents> {
        require(limit > 0) { "Event read limit must be positive" }
        return mutex.withLock {
            events
                .sortedWith(compareBy<StoredAnalyticsEvent> { it.event.created }.thenBy { it.event.clientId })
                .take(limit)
                .groupBy { it.contextId }
                .values
                .map { records ->
                    ContextEvents(
                        contextId = records.first().contextId,
                        context = records.first().context,
                        events = records.map { it.event },
                    )
                }
        }
    }

    override suspend fun remove(clientIds: Set<String>) {
        if (clientIds.isEmpty()) return
        mutex.withLock { events.removeAll { it.event.clientId in clientIds } }
    }

    override suspend fun size(): Int = mutex.withLock { events.size }
}
