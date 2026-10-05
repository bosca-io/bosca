package bosca.analytics.providers.api

import bosca.analytics.api.AnalyticsEventFactory
import bosca.analytics.api.DefaultAnalyticsEventFactory
import bosca.analytics.instrumentation.AnalyticsPageState
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Provides portable analytics event creation. */
@Providers
class AnalyticsEventFactoryProvider {
    @Provider(singleton = true)
    fun eventFactory(pageState: AnalyticsPageState): AnalyticsEventFactory =
        DefaultAnalyticsEventFactory(pageState)
}
