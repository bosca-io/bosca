package bosca.core.platform.providers

import bosca.core.analytics.InstallationIdProvider
import bosca.core.analytics.AnalyticsSessionIdProvider
import bosca.di.asProvider
import bosca.graphql.client.BoscaGraphQLHeaders
import bosca.graphql.client.KtorGraphQLClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreProvidersTest {
    @Test
    fun `GraphQL client receives Bosca application and installation identity`() = runTest {
        var sessionId = "session-1"
        val engine = MockEngine { request ->
            assertEquals(sessionId, request.headers[BoscaGraphQLHeaders.SESSION_ID])
            assertEquals("installation-1", request.headers[BoscaGraphQLHeaders.INSTALLATION_ID])
            assertEquals("reader", request.headers[BoscaGraphQLHeaders.APP_ID])
            assertEquals("6.20.0", request.headers[BoscaGraphQLHeaders.APP_VERSION])
            respond(
                content = """{"data":{"ok":true}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val infoProvider = boscaGraphQLClientInfoProvider(
            BoscaClientConfig(appId = "reader", appVersion = "6.20.0").asProvider(),
            InstallationIdProvider { "installation-1" }.asProvider(),
            AnalyticsSessionIdProvider { sessionId }.asProvider(),
        )
        val client = KtorGraphQLClient(
            endpoint = "https://api.test/graphql",
            httpClient = HttpClient(engine),
            boscaInfoProvider = infoProvider,
        )

        client.execute("query Test { ok }", null, "Test")
        sessionId = "session-2"
        client.execute("query Test { ok }", null, "Test")
    }

    @Test
    fun `GraphQL request continues without identity and retries after installation ID failure`() = runTest {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            if (requestCount == 1) {
                assertNull(request.headers[BoscaGraphQLHeaders.INSTALLATION_ID])
                assertNull(request.headers[BoscaGraphQLHeaders.APP_ID])
                assertNull(request.headers[BoscaGraphQLHeaders.APP_VERSION])
            } else {
                assertEquals("installation-1", request.headers[BoscaGraphQLHeaders.INSTALLATION_ID])
                assertEquals("reader", request.headers[BoscaGraphQLHeaders.APP_ID])
                assertEquals("6.20.0", request.headers[BoscaGraphQLHeaders.APP_VERSION])
            }
            respond(
                content = """{"data":{"ok":true}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        var attempts = 0
        val infoProvider = boscaGraphQLClientInfoProvider(
            BoscaClientConfig(appId = "reader", appVersion = "6.20.0").asProvider(),
            InstallationIdProvider {
                attempts++
                if (attempts == 1) error("not ready")
                "installation-1"
            }.asProvider(),
        )
        val client = KtorGraphQLClient(
            endpoint = "https://api.test/graphql",
            httpClient = HttpClient(engine),
            boscaInfoProvider = infoProvider,
        )

        client.execute("query Test { ok }", null, "Test")
        client.execute("query Test { ok }", null, "Test")

        assertEquals(2, attempts)
        assertEquals(2, requestCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `GraphQL identity waits at most three seconds and retries after timeout`() = runTest {
        var attempts = 0
        val infoProvider = boscaGraphQLClientInfoProvider(
            BoscaClientConfig(appId = "reader", appVersion = "6.20.0").asProvider(),
            InstallationIdProvider {
                attempts++
                if (attempts == 1) delay(5_000)
                "installation-1"
            }.asProvider(),
        )

        assertNull(infoProvider())
        assertEquals(3_000, testScheduler.currentTime)
        assertEquals("installation-1", infoProvider()?.installationId)
        assertEquals(2, attempts)
    }
}
