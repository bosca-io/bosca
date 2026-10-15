package bosca.analytics.repository

import bosca.analytics.model.Events

interface EventRepository {

    /** Stores events, propagating primary storage failures so the caller can retry delivery. */
    suspend fun process(events: Events)

    /** Flushes buffered events, propagating failures from primary storage. */
    suspend fun flush()
}
