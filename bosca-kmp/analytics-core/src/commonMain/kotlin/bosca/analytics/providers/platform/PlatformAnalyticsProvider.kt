package bosca.analytics.providers.platform

import bosca.analytics.api.AnalyticsService
import bosca.analytics.platform.PlatformAnalyticsAdapter
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Provides the client-core compatibility contract without exposing the service implementation. */
@Providers
class PlatformAnalyticsProvider {
    @Provider(singleton = true)
    fun platformAnalytics(service: AnalyticsService): bosca.core.platform.Analytics =
        PlatformAnalyticsAdapter(service)
}
