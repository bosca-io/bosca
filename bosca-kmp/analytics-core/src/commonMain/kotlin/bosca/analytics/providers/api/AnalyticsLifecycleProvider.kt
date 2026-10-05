package bosca.analytics.providers.api

import bosca.analytics.api.AnalyticsLifecycle
import bosca.analytics.api.AnalyticsService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Exposes the DI-created analytics service through its lifecycle contract. */
@Providers
class AnalyticsLifecycleProvider {
    @Provider(singleton = true)
    fun lifecycle(service: AnalyticsService): AnalyticsLifecycle = service
}
