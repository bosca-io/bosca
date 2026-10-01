package bosca.analytics.providers

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsLifecycle
import bosca.analytics.api.AnalyticsService
import bosca.analytics.delivery.AnonymousInstallationIdProvider
import bosca.analytics.delivery.AnalyticsUserIdProvider
import bosca.analytics.delivery.BoscaSinkConfig
import bosca.analytics.delivery.HttpInstallationIdProvider
import bosca.analytics.persistence.AnalyticsEventStore
import bosca.analytics.persistence.InMemoryAnalyticsEventStore
import bosca.core.analytics.InstallationIdProvider
import bosca.core.preferences.Preferences
import bosca.di.AnalyticsProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.di.register
import bosca.graphql.client.GraphQLRequestInstrumentation
import bosca.graphql.client.KtorGraphQLClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(InternalDI::class)
class AnalyticsProvidersTest {
    @Test
    fun `delivery providers select the analytics owned installation implementation`() {
        val providers = AnalyticsDeliveryProviders()
        val preferences = FakePreferences()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })

        assertIs<AnonymousInstallationIdProvider>(
            providers.installationIdProvider(config(anonymous = true), preferences, client),
        )
        assertIs<HttpInstallationIdProvider>(
            providers.installationIdProvider(config(anonymous = false), preferences, client),
        )
        client.close()
    }

    @Test
    fun `generated registrar composes one analytics service behind public contracts`() = runTest {
        ProviderRegistry.clear()
        val store = InMemoryAnalyticsEventStore()
        val installationIds = InstallationIdProvider { "analytics-installation" }
        val requestInstrumentation = GraphQLRequestInstrumentation()
        register(AnalyticsProviderRegistrar())
        provides(singleton = true) {
            BoscaSinkConfig(
                url = "https://analytics.test",
                appId = "test-app",
                appVersion = "1",
                clientId = "test-client",
                anonymous = true,
                autoFlush = false,
                heartbeat = false,
                sessionTracking = false,
            )
        }
        provides<AnalyticsEventStore>(singleton = true, overrideExisting = true) { store }
        provides<AnalyticsUserIdProvider>(singleton = true, overrideExisting = true) {
            AnalyticsUserIdProvider.Anonymous
        }
        provides<InstallationIdProvider>(singleton = true, overrideExisting = true) { installationIds }
        provides<GraphQLRequestInstrumentation>(singleton = true, overrideExisting = true) {
            requestInstrumentation
        }
        provides<HttpClient>(
            name = ANALYTICS_HTTP_CLIENT,
            singleton = true,
            overrideExisting = true,
        ) {
            HttpClient(MockEngine { respond("", HttpStatusCode.Accepted) })
        }

        try {
            val service = provide<AnalyticsService>()
            val lifecycle = provide<AnalyticsLifecycle>()
            val clientCore = provide<bosca.core.platform.Analytics>()

            assertSame(service, lifecycle)
            assertSame(installationIds, provide<InstallationIdProvider>())
            assertEquals(
                provide<bosca.analytics.delivery.BoscaSink>().sessionId(),
                provide<bosca.core.analytics.AnalyticsSessionIdProvider>().sessionId(),
            )

            lifecycle.start()
            service.logInteraction(AnalyticsElement("save", "button"))
            clientCore.logEvent("opened")
            KtorGraphQLClient(
                endpoint = "https://api.test/graphql",
                httpClient = HttpClient(MockEngine { respond("""{"data":{}}""") }),
                instrumentation = requestInstrumentation,
            ).execute("query CurrentUser { currentUser { id } }", null, "CurrentUser")
            withTimeout(2_000) {
                while (store.size() < 3) delay(1)
            }
            assertEquals(3, store.size())
            val requests = store.read(10).flatMap { it.events }.filter { it.element.type == "graphql_request" }
            assertEquals("CurrentUser", requests.single().element.id)
            lifecycle.close()
            lifecycle.close()
            assertEquals(0, store.size())
        } finally {
            ProviderRegistry.clear()
        }
    }

    private fun config(anonymous: Boolean) = BoscaSinkConfig(
        url = "https://analytics.test",
        appId = "test-app",
        appVersion = "1",
        clientId = "test-client",
        anonymous = anonymous,
    )

    private class FakePreferences : Preferences {
        private val values = mutableMapOf<String, MutableStateFlow<String?>>()

        override fun getString(key: String): Flow<String?> = values.getOrPut(key) { MutableStateFlow(null) }

        override suspend fun setString(key: String, value: String?) {
            values.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
    }
}
