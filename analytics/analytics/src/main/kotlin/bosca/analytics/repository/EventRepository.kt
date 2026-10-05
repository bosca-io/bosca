package bosca.analytics.repository

import bosca.analytics.model.Events

interface EventRepository {

    suspend fun process(events: Events)

    suspend fun flush()
}