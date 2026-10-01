package bosca.analytics.providers

import bosca.analytics.api.AnalyticsEventFactory
import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.delivery.AnalyticsUserIdProvider
import bosca.analytics.delivery.AnonymousInstallationIdProvider
import bosca.analytics.delivery.BoscaSink
import bosca.analytics.delivery.BoscaSinkConfig
import bosca.analytics.delivery.HttpInstallationIdProvider
import bosca.analytics.persistence.AnalyticsEventStore
import bosca.core.analytics.InstallationIdProvider
import bosca.core.analytics.AnalyticsSessionIdProvider
import bosca.core.preferences.Preferences
import bosca.core.security.BoscaAuth
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import io.ktor.client.HttpClient

/** Bosca DI providers for installation identity and collector delivery. */
@Providers
class AnalyticsDeliveryProviders {
    /** Exposes the sink's live session identity to other SDK clients. */
    @Provider(singleton = true)
    fun sessionIdProvider(sink: BoscaSink): AnalyticsSessionIdProvider = AnalyticsSessionIdProvider(sink::sessionId)

    @Provider(singleton = true)
    fun userIdProvider(auth: BoscaAuth): AnalyticsUserIdProvider = AnalyticsUserIdProvider {
        auth.currentUser.value?.id?.toString()
    }

    @Provider(singleton = true)
    fun installationIdProvider(
        config: BoscaSinkConfig,
        preferences: Preferences,
        @ProviderName(ANALYTICS_HTTP_CLIENT) client: HttpClient,
    ): InstallationIdProvider = if (config.anonymous) {
        AnonymousInstallationIdProvider()
    } else {
        HttpInstallationIdProvider(config, client, preferences)
    }

    @Provider(singleton = true)
    fun sink(
        config: BoscaSinkConfig,
        eventStore: AnalyticsEventStore,
        installationIdProvider: InstallationIdProvider,
        runtimeScope: AnalyticsRuntimeScope,
        eventFactory: AnalyticsEventFactory,
        logger: AnalyticsLogger,
        userIdProvider: AnalyticsUserIdProvider,
        @ProviderName(ANALYTICS_HTTP_CLIENT) client: HttpClient,
    ): BoscaSink = BoscaSink(
        config = config,
        client = client,
        eventStore = eventStore,
        installationProvider = installationIdProvider,
        scope = runtimeScope.scope,
        eventFactory = eventFactory,
        logger = logger,
        userIdProvider = userIdProvider,
    )
}
