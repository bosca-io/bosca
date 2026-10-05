package bosca.analytics.providers

import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.delivery.defaultAnalyticsLogger
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import io.ktor.client.HttpClient

/** Bosca DI providers for analytics-owned infrastructure. */
@Providers
class AnalyticsInfrastructureProviders {
    @Provider(singleton = true, name = ANALYTICS_HTTP_CLIENT)
    fun httpClient(): HttpClient = HttpClient()

    @Provider(singleton = true)
    fun logger(): AnalyticsLogger = defaultAnalyticsLogger()

    @Provider(singleton = true)
    fun runtimeScope(): AnalyticsRuntimeScope = AnalyticsRuntimeScope()
}
