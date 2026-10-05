package bosca.analytics.persistence

/** Structured persistence boundary for pending analytics events. */
interface AnalyticsEventStore {
    /** Adds one event and its context snapshot. */
    suspend fun add(event: StoredAnalyticsEvent)

    /** Reads context groups containing at most [limit] pending events in delivery order. */
    suspend fun read(limit: Int): List<ContextEvents>

    /** Removes successfully delivered events by client ID. */
    suspend fun remove(clientIds: Set<String>)

    /** Returns the number of pending events. */
    suspend fun size(): Int
}
