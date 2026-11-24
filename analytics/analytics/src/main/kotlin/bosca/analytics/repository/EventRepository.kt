package bosca.analytics.repository

import bosca.analytics.model.Events

interface EventRepository {

    /** Stores events, propagating primary storage failures so the caller can retry delivery. */
    suspend fun process(events: Events)

    /**
     * Commits previously buffered events to primary storage before returning, including data
     * whose append was started by another caller. Storage failures propagate to every caller
     * waiting for that data until it is committed.
     */
    suspend fun flush()

    /**
     * Commits [batches] to primary storage as one unit before returning. When storage fails, none
     * of [batches] remains buffered for a later commit, so redelivery by the caller is the only
     * retry and cannot duplicate them.
     */
    suspend fun store(batches: List<Events>) {
        for (events in batches) process(events)
        flush()
    }
}
