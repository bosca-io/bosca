package bosca.analytics.providers.api

import bosca.analytics.api.AnalyticsEventFactory
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsRuntimeScope
import bosca.analytics.api.AnalyticsService
import bosca.analytics.api.BoscaAnalytics
import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.instrumentation.AnalyticsPageState
import bosca.analytics.instrumentation.AutomaticInstrumentationOptions
import bosca.analytics.lifecycle.AnalyticsResources
import bosca.analytics.lifecycle.ManagedAnalyticsService
import bosca.analytics.persistence.AnalyticsPersistence
import bosca.analytics.providers.ANALYTICS_HTTP_CLIENT
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import io.ktor.client.HttpClient
import bosca.graphql.client.GraphQLRequestInstrumentation

/** Provides the primary analytics API contract and owns its DI-created resources. */
@Providers
class AnalyticsServiceProvider {
    @Provider(singleton = true)
    fun analyticsService(
        factory: AnalyticsEventFactory,
        sink: AnalyticsEventSink,
        pageState: AnalyticsPageState,
        runtimeScope: AnalyticsRuntimeScope,
        options: AutomaticInstrumentationOptions,
        logger: AnalyticsLogger,
        requestInstrumentation: GraphQLRequestInstrumentation,
        persistence: AnalyticsPersistence,
        @ProviderName(ANALYTICS_HTTP_CLIENT) client: HttpClient,
    ): AnalyticsService = ManagedAnalyticsService(
        delegate = BoscaAnalytics(
            factory = factory,
            sink = sink,
            pageState = pageState,
            runtimeScope = runtimeScope,
            automaticInstrumentationOptions = options,
            logger = logger,
            requestInstrumentation = requestInstrumentation,
        ),
        resources = AnalyticsResources(runtimeScope, client, persistence),
    )
}
