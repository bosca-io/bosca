package bosca.analytics.providers

import bosca.analytics.delivery.BoscaSinkConfig
import bosca.analytics.experimentation.persistence.FeatureFlagCacheStore
import bosca.analytics.persistence.AnalyticsEventStore
import bosca.analytics.persistence.AnalyticsPersistence
import bosca.analytics.persistence.room.RoomAnalyticsEventStore
import bosca.analytics.persistence.room.RoomFeatureFlagCacheStore
import bosca.analytics.platform.createAnalyticsDatabase
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Bosca DI providers for Room-backed analytics persistence. */
@Providers
class AnalyticsPersistenceProviders {
    @Provider(singleton = true)
    fun persistence(): AnalyticsPersistence = AnalyticsPersistence(createAnalyticsDatabase())

    @Provider(singleton = true)
    fun eventStore(persistence: AnalyticsPersistence, config: BoscaSinkConfig): AnalyticsEventStore =
        RoomAnalyticsEventStore(persistence.database, config.storageNamespace)

    @Provider(singleton = true)
    fun featureFlagCache(persistence: AnalyticsPersistence): FeatureFlagCacheStore =
        RoomFeatureFlagCacheStore(persistence.database)
}
