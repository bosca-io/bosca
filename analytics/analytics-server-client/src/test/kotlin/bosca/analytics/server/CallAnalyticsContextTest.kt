package bosca.analytics.server

import bosca.server.ServerCall
import bosca.server.RequestHeaders
import io.netty.handler.codec.http.DefaultHttpHeaders
import bosca.server.auth.CallAuthenticationContext
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallAnalyticsContextTest {

    @Test
    fun `request scope sees authentication and overrides applied after entry`() = runTest {
        val call = fakeCall()
        val authentication = call.authenticationContext
        call.withAnalyticsContext {
            assertEquals(null, analyticsContext().userId)
            val principal = bosca.security.model.AuthenticatedPrincipal(
                bosca.security.model.Principal(), emptyList(),
            )
            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
            authentication.principal("test", principal)
            assertEquals(principal.id.toString(), analyticsContext().userId)
            withAnalyticsContext("source" to "nested") {
                call.analyticsContext = AnalyticsContext(sessionId = "updated")
                assertEquals("updated", analyticsContext().sessionId)
                assertEquals("nested", analyticsContext().extras["source"])
            }
        }
        assertEquals(AnalyticsContext(), analyticsContext())
    }

    private fun fakeCall(): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        val attrs = java.util.concurrent.ConcurrentHashMap<String, Any>()
        every { call.attributes } returns attrs
        every { call.request.headers } returns RequestHeaders(DefaultHttpHeaders())
        every { call.authenticationContext } returns CallAuthenticationContext()
        return call
    }

    @Test
    fun `reading analytics context does not allocate overrides`() {
        val call = fakeCall()
        assertTrue(call.attributes[ANALYTICS_CONTEXT_ATTRIBUTE_KEY] == null)
        assertEquals(AnalyticsContext(), call.analyticsContext)
        assertTrue(call.attributes[ANALYTICS_CONTEXT_ATTRIBUTE_KEY] == null)
    }

    @Test
    fun `typed context can be replaced without mutating an earlier snapshot`() {
        val call = fakeCall()
        val first = call.analyticsContext
        call.analyticsContext = first.copy(sessionId = "session")
        assertEquals("session", call.analyticsContext.sessionId)
        assertEquals(null, first.sessionId)
        assertTrue(call.attributes[ANALYTICS_CONTEXT_ATTRIBUTE_KEY] is AnalyticsContext)
    }

    @Test
    fun `typed context preserves explicit null overrides`() {
        val call = fakeCall()
        every { call.request.headers } returns RequestHeaders(DefaultHttpHeaders().add("X-BA-Session-ID", "request-session"))
        call.analyticsContext = AnalyticsContext(extras = mapOf("explicitlyNull" to null, "session_id" to null))
        assertTrue(call.analyticsContext.extras.containsKey("explicitlyNull"))
        assertEquals(null, call.analyticsContext.sessionId)
    }

    @Test
    fun `copy can clear request identity in the call scope and captured errors`() = runTest {
        val call = fakeCall()
        every { call.request.headers } returns RequestHeaders(DefaultHttpHeaders().apply {
            add("X-App-ID", "app")
            add("X-BA-Session-ID", "request-session")
        })
        withAnalyticsContext(AnalyticsContext(sessionId = "parent-session")) {
            call.withAnalyticsContext {
                assertEquals("request-session", analyticsContext().sessionId)
                call.analyticsContext = call.analyticsContext.copy(sessionId = null)
                assertEquals("app", call.analyticsContext.appId)
                assertEquals(null, call.analyticsContext.sessionId)
                assertEquals(null, analyticsContext().sessionId)
                val resolved = AnalyticsErrorContextResolver.resolve(
                    call, analyticsContext().toMap(), emptyMap(), mapOf("session_id" to "fallback"),
                )
                assertTrue(resolved.containsKey("session_id"))
                assertEquals(null, resolved["session_id"])
            }
            assertEquals("parent-session", analyticsContext().sessionId)
        }
    }

    @Test
    fun `call scope carries case insensitive request identity and restores ambient context`() = runTest {
        val call = fakeCall()
        every { call.request.headers } returns RequestHeaders(DefaultHttpHeaders().apply {
            add("x-app-id", "app")
            add("x-app-version", "42")
            add("x-installation-id", "install")
            add("x-ba-session-id", "session")
        })
        call.analyticsContext = call.analyticsContext.copy(extras = mapOf("jobId" to "job"))
        val expected = AnalyticsContext(appId = "app", appVersion = "42", installationId = "install",
            sessionId = "session", extras = mapOf("jobId" to "job"))
        call.withAnalyticsContext {
            assertEquals(AnalyticsContext.fromEntries(expected.toMap(true)), analyticsContext())
        }
        assertEquals(AnalyticsContext(), analyticsContext())
        call.attributes[AnalyticsMiddleware.ANALYTICS_SESSION_ID_ATTRIBUTE] = "attribute-session"
        assertEquals("session", call.analyticsContext.sessionId)
        call.analyticsContext = call.analyticsContext.copy(sessionId = "override-session")
        assertEquals("override-session", call.analyticsContext.sessionId)
    }

    @Test
    fun `missing call and missing headers preserve enclosing analytics identity through cancellation`() = runTest {
        val outer = AnalyticsContext(sessionId = "session")
        withAnalyticsContext(outer) {
            val missing: ServerCall? = null
            missing.withAnalyticsContext { assertEquals(outer, analyticsContext()) }
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                fakeCall().withAnalyticsContext {
                    assertEquals(outer, analyticsContext())
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }
            assertEquals(outer, analyticsContext())
        }
        assertEquals(AnalyticsContext(), analyticsContext())
    }
}
