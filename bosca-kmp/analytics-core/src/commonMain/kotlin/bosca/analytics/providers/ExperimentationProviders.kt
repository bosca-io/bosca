package bosca.analytics.providers

import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.experimentation.FeatureFlagClient
import bosca.analytics.experimentation.FeatureFlagOptions
import bosca.analytics.experimentation.persistence.FeatureFlagCacheStore
import bosca.core.analytics.InstallationIdProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLSubscriptionClient

/** Bosca DI providers for analytics-backed experimentation. */
@Providers
class ExperimentationProviders {
    @Provider(singleton = true)
    fun featureFlags(
        options: FeatureFlagOptions,
        cacheStore: FeatureFlagCacheStore,
        installationIdProvider: InstallationIdProvider,
        runtimeScope: AnalyticsRuntimeScope,
        logger: AnalyticsLogger,
        client: GraphQLClient,
        subscriptions: GraphQLSubscriptionClient,
    ): FeatureFlagClient = FeatureFlagClient(
        options = options,
        client = client,
        subscriptions = subscriptions,
        cacheStore = cacheStore,
        installationIdProvider = installationIdProvider,
        scope = runtimeScope.scope,
        logger = logger,
    )
}
