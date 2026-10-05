package bosca.analytics.persistence

import bosca.analytics.persistence.room.AnalyticsDatabase

/** DI-owned handle to the shared Room database used by analytics services. */
class AnalyticsPersistence internal constructor(
    internal val database: AnalyticsDatabase,
) {
    internal fun close() {
        database.close()
    }
}
