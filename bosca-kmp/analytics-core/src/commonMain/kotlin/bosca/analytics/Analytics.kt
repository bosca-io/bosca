package bosca.analytics

import bosca.analytics.api.AnalyticsService
import bosca.analytics.delivery.BoscaSinkConfig
import bosca.core.platform.providers.Urls
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.provide
import bosca.di.provideBlockingNoSuspend
import bosca.di.provides
import bosca.di.register

/** Configures, registers, and starts the Bosca analytics runtime for an application. */
object Analytics {
    /**
     * Initializes analytics after the application's ordinary Bosca providers have been registered.
     * The returned service is the DI-owned singleton and is already started.
     *
     * @param appId stable application identifier reported with every event
     * @param clientId client variant used to isolate durable analytics storage
     */
    fun initialize(
        appId: String,
        clientId: String = "mobile",
    ): AnalyticsService = initializeWithConfig {
        BoscaSinkConfig(
            urls = provide<Urls>(),
            appId = appId,
            clientId = clientId,
        )
    }

    /**
     * Initializes analytics with an explicit delivery [config]. Use this overload when the
     * application needs to customize delivery or automatic instrumentation behavior.
     */
    fun initialize(config: BoscaSinkConfig): AnalyticsService = initializeWithConfig { config }

    private fun initializeWithConfig(config: suspend () -> BoscaSinkConfig): AnalyticsService {
        provides<BoscaSinkConfig>(singleton = true) {
            config()
        }
        register(AnalyticsProviderRegistrar())
        return provideBlockingNoSuspend<AnalyticsService>().also(AnalyticsService::start)
    }
}
