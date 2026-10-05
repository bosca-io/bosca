package bosca.analytics.providers.api

import bosca.analytics.instrumentation.AnalyticsPageState
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Provides the page state shared by event creation and Compose instrumentation. */
@Providers
class AnalyticsPageStateProvider {
    @Provider(singleton = true)
    fun pageState(): AnalyticsPageState = AnalyticsPageState()
}
