package bosca.analytics.server

import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.auth.CallAuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AnalyticsMiddlewareTest {

    private fun mockCall(
        method: String = "POST",
        path: String = "/api/v1/things/42",
        uri: String = "/api/v1/things/42?x=1",
        remote: String? = "10.1.2.3",
        routePattern: String? = "/api/v1/things/{id}",
        status: HttpStatusCode? = HttpStatusCode.InternalServerError,
        headers: Map<String, String> = mapOf(
            "user-agent" to "test/1.0",
            "x-request-id" to "req-1",
            "authorization" to "Bearer secret",
        ),
        principal: AuthenticatedPrincipal? = null,
    ): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        val request = mockk<ServerRequest>(relaxed = true)
        val response = mockk<ServerResponse>(relaxed = true)
        every { call.request } returns request
        every { call.response } returns response
        every { request.httpMethod } returns HttpMethod.parse(method)
        every { request.path } returns path
        every { request.uri } returns uri
        every { request.remoteAddress } returns remote
        for ((k, v) in headers) every { request.headers[k] } returns v
        every { request.headers[match { it !in headers.keys }] } returns null
        every { response.status() } returns status
        val attrs = java.util.concurrent.ConcurrentHashMap<String, Any>()
        if (routePattern != null) attrs[AnalyticsMiddleware.ROUTING_PATTERN_ATTRIBUTE] = routePattern
        every { call.attributes } returns attrs
        val authContext = CallAuthenticationContext()
        if (principal != null) {
            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
            authContext.principal("test", principal)
        }
        every { call.authenticationContext } returns authContext
        return call
    }

    private class FakeClient : ServerAnalyticsClient {
        var lastException: Throwable? = null
        var lastFatal: Boolean? = null
        var lastSessionId: String? = null
        var lastUserId: String? = null
        var lastContext: Map<String, Any?> = emptyMap()
        var captureCalls = 0

        override suspend fun capture(event: Event) = Unit
        override suspend fun captureForSubject(
            event: Event,
            userId: String?,
            installationId: String?,
            device: bosca.analytics.model.Device?,
        ) = Unit
        override suspend fun capture(events: Events) = Unit
        override suspend fun captureException(
            throwable: Throwable,
            fatal: Boolean,
            appId: String?,
            sessionId: String?,
            userId: String?,
            context: Map<String, Any?>,
        ) {
            captureCalls += 1
            lastException = throwable
            lastFatal = fatal
            lastSessionId = sessionId
            lastUserId = userId
            lastContext = context
        }
        override suspend fun flush() = Unit
    }

    @Test
    fun `onException routes throwable to the client with request context`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        val cause = IllegalStateException("boom")
        middleware.onException(mockCall(), cause)

        assertEquals(1, client.captureCalls)
        assertEquals(cause, client.lastException)
        assertEquals(true, client.lastFatal, "5xx responses should mark the error as fatal")
        assertEquals("POST", client.lastContext["http.method"])
        assertEquals("/api/v1/things/42", client.lastContext["http.path"])
        assertEquals("/api/v1/things/{id}", client.lastContext["http.route"])
        assertEquals("10.1.2.3", client.lastContext["http.remote_address"])
        assertEquals(500, client.lastContext["http.status_code"])
        assertEquals("test/1.0", client.lastContext["http.header.user-agent"])
        assertEquals("req-1", client.lastContext["http.header.x-request-id"])
    }

    @Test
    fun `authorization header is never copied into the captured context`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        middleware.onException(mockCall(), RuntimeException("x"))
        assertTrue(client.lastContext.keys.none { it.contains("authorization") })
    }

    @Test
    fun `principal id is forwarded as the userId`() = runTest {
        val principalId = Uuid.random()
        val principal = AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        middleware.onException(mockCall(principal = principal), RuntimeException("x"))
        assertEquals(principalId.toString(), client.lastUserId)
    }

    @Test
    fun `analytics session id is forwarded to the client`() = runTest {
        val client = FakeClient()
        val call = mockCall()
        call.attributes[AnalyticsMiddleware.ANALYTICS_SESSION_ID_ATTRIBUTE] = "session-1"

        AnalyticsMiddleware(client, appId = "test-app")
            .onException(call, RuntimeException("x"))

        assertEquals("session-1", client.lastSessionId)
    }

    @Test
    fun `request session header is captured and ambient session overrides it`() = runTest {
        val client = FakeClient()
        val call = mockCall(headers = mapOf("X-BA-Session-ID" to "request-session"))
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        middleware.onException(call, RuntimeException("request"))
        assertEquals("request-session", client.lastSessionId)
        withAnalyticsContext(AnalyticsContext(sessionId = "ambient-session")) {
            middleware.onException(call, RuntimeException("ambient"))
        }
        assertEquals("ambient-session", client.lastSessionId)
        withAnalyticsContext("session_id" to null) {
            middleware.onException(call, RuntimeException("cleared"))
        }
        kotlin.test.assertNull(client.lastSessionId)
    }

    @Test
    fun `non-5xx responses are not marked fatal when configured to derive from status`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        val call = mockCall(status = HttpStatusCode.BadRequest)
        middleware.onException(call, RuntimeException("x"))
        assertEquals(false, client.lastFatal)
    }

    @Test
    fun `null response status defaults to fatal`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        val call = mockCall(status = null)
        middleware.onException(call, RuntimeException("x"))
        assertEquals(true, client.lastFatal)
    }

    @Test
    fun `call analyticsContext entries are merged into the captured context`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        val call = mockCall()
        call.analyticsContext = AnalyticsContext(extras = mapOf("workflowId" to "wf-1"))
        middleware.onException(call, RuntimeException("x"))
        assertEquals("wf-1", client.lastContext["workflowId"])
    }

    @Test
    fun `coroutine context entries are merged into the captured context`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        val call = mockCall()
        withAnalyticsContext("requestId" to "rid-7") {
            middleware.onException(call, RuntimeException("x"))
        }
        assertEquals("rid-7", client.lastContext["requestId"])
    }

    @Test
    fun `client failures inside the middleware are swallowed`() = runTest {
        val client = object : ServerAnalyticsClient {
            override suspend fun capture(event: Event) = Unit
            override suspend fun captureForSubject(
                event: Event,
                userId: String?,
                installationId: String?,
                device: bosca.analytics.model.Device?,
            ) = Unit
            override suspend fun capture(events: Events) = Unit
            override suspend fun captureException(
                throwable: Throwable, fatal: Boolean, appId: String?,
                sessionId: String?, userId: String?, context: Map<String, Any?>,
            ): Unit = throw RuntimeException("downstream broke")
            override suspend fun flush() = Unit
        }
        val middleware = AnalyticsMiddleware(client, appId = "test-app")
        // Must not throw
        middleware.onException(mockCall(), RuntimeException("original"))
    }

    @Test
    fun `markServerErrorsAsFatal=false always reports non-fatal`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app", markServerErrorsAsFatal = false)
        middleware.onException(mockCall(), RuntimeException("x"))
        assertEquals(false, client.lastFatal)
    }

    @Test
    fun `header allow list filters out unlisted headers`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app", headerAllowList = setOf("x-request-id"))
        middleware.onException(mockCall(), RuntimeException("x"))
        assertNotNull(client.lastContext["http.header.x-request-id"])
        assertEquals(null, client.lastContext["http.header.user-agent"])
    }

    @Test
    fun `missing optional request values are omitted from context`() = runTest {
        val client = FakeClient()
        val middleware = AnalyticsMiddleware(client, appId = "test-app")

        middleware.onException(
            mockCall(remote = null, routePattern = null, status = null, headers = emptyMap()),
            RuntimeException("x"),
        )

        assertEquals(null, client.lastContext["http.remote_address"])
        assertEquals(null, client.lastContext["http.route"])
        assertEquals(null, client.lastContext["http.status_code"])
        assertTrue(client.lastContext.keys.none { it.startsWith("http.header.") })
    }

    @Test
    fun `context collection failure falls back to minimal context`() = runTest {
        val client = FakeClient()
        val call = mockCall()
        every { call.request } throws IllegalStateException("request unavailable")
        val cause = object : IllegalArgumentException("original") {}
        val middleware = AnalyticsMiddleware(client, appId = "test-app")

        middleware.onException(call, IllegalArgumentException("named"))
        assertEquals("IllegalArgumentException", client.lastContext["original_error_type"])

        middleware.onException(call, cause)

        assertEquals(true, client.lastContext["context_collection_failed"])
        assertEquals("Unknown", client.lastContext["original_error_type"])
    }

    @Test
    fun `context collection cancellation is preserved`() = runTest {
        val call = mockCall()
        every { call.request } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            AnalyticsMiddleware(FakeClient(), appId = "test-app")
                .onException(call, RuntimeException("original"))
        }
    }

    @Test
    fun `client cancellation is preserved`() = runTest {
        val client = object : ServerAnalyticsClient {
            override suspend fun capture(event: Event) = Unit
            override suspend fun captureForSubject(
                event: Event,
                userId: String?,
                installationId: String?,
                device: bosca.analytics.model.Device?,
            ) = Unit
            override suspend fun capture(events: Events) = Unit
            override suspend fun captureException(
                throwable: Throwable,
                fatal: Boolean,
                appId: String?,
                sessionId: String?,
                userId: String?,
                context: Map<String, Any?>,
            ): Unit = throw CancellationException("cancelled")

            override suspend fun flush() = Unit
        }

        assertFailsWith<CancellationException> {
            AnalyticsMiddleware(client, appId = "test-app")
                .onException(mockCall(), RuntimeException("original"))
        }
    }

    @Test
    fun `sensitive headers are rejected from the allow list`() {
        for (header in listOf("authorization", "cookie", "proxy-authorization")) {
            assertFailsWith<IllegalArgumentException> {
                AnalyticsMiddleware(FakeClient(), appId = "test-app", headerAllowList = setOf(header))
            }
        }
    }
}
