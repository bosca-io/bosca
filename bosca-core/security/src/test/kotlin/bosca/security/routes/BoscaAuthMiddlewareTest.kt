package bosca.security.routes

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.model.Principal
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthCookiePrefix
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.authCookieDomain
import bosca.security.session.Session
import bosca.server.HttpStatusCode
import bosca.server.RequestCookies
import bosca.server.RequestOrigin
import bosca.server.ResponseCookies
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.auth.CallAuthenticationContext
import bosca.server.routing.AuthConfig
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class, Internal::class)
class BoscaAuthMiddlewareTest {

    private val securityConfiguration = mockk<SecurityConfiguration>(relaxed = true)
    private val connectionPool = mockk<ConnectionPool>()
    private val securityService = mockk<SecurityService>()
    private val apiTokenService = mockk<ApiTokenService>()
    private val tracer = mockk<Tracer>()
    private val errorCapture = mockk<ErrorCapture>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private val span = mockk<Span>(relaxed = true)
    private val spanBuilder = mockk<SpanBuilder>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val testPrincipal = AuthenticatedPrincipal(Principal(), emptyList())
    private val testScopedPrincipal = ScopedAuthenticatedPrincipal(
        principal = Principal(),
        allGroups = emptyList(),
        scopes = null,
        allowedGroupIds = null,
        credentialId = 1L,
    )

    private lateinit var middleware: BoscaAuthMiddleware

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(Json { ignoreUnknownKeys = true }) }

        mockkStatic("bosca.db.ConnectionManagerKt")

        every { tracer.spanBuilder(any()) } returns spanBuilder
        every { spanBuilder.startSpan() } returns span
        every { connectionPool.connection() } returns connectionManager
        every { connectionManager.asCoroutineContext() } returns EmptyCoroutineContext
        coEvery { connectionManager.release() } returns Unit
        every { securityConfiguration.authCookiePrefixes } returns emptyList()

        middleware = BoscaAuthMiddleware(
            securityConfiguration = securityConfiguration,
            connectionPool = connectionPool,
            securityService = securityService,
            apiTokenService = apiTokenService,
            tracer = tracer,
            cookieMaxAge = 3600L,
            errorCapture = errorCapture,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private fun mockCall(
        authorizationHeader: String? = null,
        cookieHeader: String? = null,
        appOrigin: String = "https://app.example",
        addressedHost: String = authCookieDomain(appOrigin) ?: "app.example",
    ): Pair<ServerCall, CallAuthenticationContext> {
        val authContext = CallAuthenticationContext()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.header("Authorization") } returns authorizationHeader
        every { request.clientIp } returns "127.0.0.1"
        every { request.cookies } returns RequestCookies(cookieHeader)
        every { request.appOrigin } returns appOrigin
        every { request.origin } returns RequestOrigin("https", addressedHost, 443)
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.authenticationContext } returns authContext
        return call to authContext
    }

    @Test
    fun `basic auth with api_token username and bsk_ password authenticates as api token`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("api_token:bsk_test_abc123".toByteArray())
        val (call, authContext) = mockCall("Basic $encoded")

        coEvery { apiTokenService.authenticate("bsk_test_abc123", "127.0.0.1") } returns testScopedPrincipal

        middleware.authenticate(call, null)

        coVerify { apiTokenService.authenticate("bsk_test_abc123", "127.0.0.1") }
        assertEquals(testScopedPrincipal, authContext.principal("api_token"))
        assertNull(authContext.principal("basic"))
    }

    @Test
    fun `basic auth with regular credentials uses credential validation`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("user@test.com:password123".toByteArray())
        val (call, authContext) = mockCall("Basic $encoded")

        coEvery { securityService.authenticateWithCredential(any()) } returns testPrincipal

        middleware.authenticate(call, null)

        val credSlot = slot<SimplePasswordAttributes>()
        coVerify { securityService.authenticateWithCredential(capture(credSlot)) }
        assertEquals("user@test.com", credSlot.captured.identifier)
        assertEquals("password123", credSlot.captured.password)
        assertEquals(testPrincipal, authContext.principal("basic"))
        assertNull(authContext.principal("api_token"))
    }

    @Test
    fun `basic auth with api_token username but non-bsk password uses credential validation`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("api_token:regular_password".toByteArray())
        val (call, authContext) = mockCall("Basic $encoded")

        coEvery { securityService.authenticateWithCredential(any()) } returns testPrincipal

        middleware.authenticate(call, null)

        coVerify { securityService.authenticateWithCredential(any()) }
        assertEquals(testPrincipal, authContext.principal("basic"))
        assertNull(authContext.principal("api_token"))
    }

    @Test
    fun `basic auth with bsk_ password but different username uses credential validation`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("other_user:bsk_test_abc123".toByteArray())
        val (call, authContext) = mockCall("Basic $encoded")

        coEvery { securityService.authenticateWithCredential(any()) } returns testPrincipal

        middleware.authenticate(call, null)

        coVerify { securityService.authenticateWithCredential(any()) }
        assertEquals(testPrincipal, authContext.principal("basic"))
    }

    @Test
    fun `basic auth api token failure returns 401`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("api_token:bsk_invalid_token".toByteArray())
        val (call, _) = mockCall("Basic $encoded")

        coEvery {
            apiTokenService.authenticate("bsk_invalid_token", "127.0.0.1")
        } throws bosca.security.service.SecurityException("Invalid token")

        middleware.authenticate(call, null)

        coVerify { call.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `bearer bsk_ token still works through bearer flow`() = runTest {
        val (call, authContext) = mockCall("Bearer bsk_test_abc123")

        coEvery { apiTokenService.authenticate("bsk_test_abc123", "127.0.0.1") } returns testScopedPrincipal

        middleware.authenticate(call, null)

        coVerify { apiTokenService.authenticate("bsk_test_abc123", "127.0.0.1") }
        assertEquals(testScopedPrincipal, authContext.principal("api_token"))
    }

    @Test
    fun `invalid bearer api token is rejected without JWT fallback`() = runTest {
        val (call, authContext) = mockCall("Bearer bsk_invalid")
        coEvery {
            apiTokenService.authenticate("bsk_invalid", "127.0.0.1")
        } throws bosca.security.service.SecurityException("invalid")

        middleware.authenticate(call, AuthConfig(emptyList(), optional = true))

        coVerify { call.respond(HttpStatusCode.Unauthorized, "") }
        coVerify(exactly = 0) { securityService.authenticateWithPayload(any()) }
        assertNull(authContext.anyPrincipal())
    }

    @Test
    fun `unexpected bearer api token failure is captured`() = runTest {
        val failure = IllegalStateException("database unavailable")
        val (call, _) = mockCall("Bearer bsk_failure")
        coEvery {
            apiTokenService.authenticate("bsk_failure", "127.0.0.1")
        } throws failure

        middleware.authenticate(call, null)

        val captured = slot<Throwable>()
        coVerify {
            errorCapture.capture(
                capture(captured),
                call,
                mapOf("auth.provider" to "api_token"),
            )
        }
        assertEquals(failure.message, captured.captured.message)
        coVerify { call.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `valid JWT bearer token authenticates and releases resources`() = runTest {
        val jwt = mockk<DecodedJWT>()
        val (call, authContext) = mockCall("Bearer signed-jwt")
        every { securityConfiguration.verifier.verify("signed-jwt") } returns jwt
        coEvery { securityService.authenticateWithPayload(jwt) } returns testPrincipal

        assertTrue(middleware.authenticateBearerToken(call, "signed-jwt"))

        assertEquals(testPrincipal, authContext.principal("bearer"))
        coVerify { connectionManager.release() }
        verify { span.end() }
    }

    @Test
    fun `JWT verification and unexpected failures return false`() = runTest {
        val verificationFailure = mockk<JWTVerificationException>()
        val unexpectedFailure = IllegalStateException("principal lookup failed")
        val (invalidCall, _) = mockCall()
        val (failureCall, _) = mockCall()
        every {
            securityConfiguration.verifier.verify("invalid-jwt")
        } throws verificationFailure
        every {
            securityConfiguration.verifier.verify("unexpected-jwt")
        } throws unexpectedFailure

        assertFalse(middleware.authenticateBearerToken(invalidCall, "invalid-jwt"))
        assertFalse(middleware.authenticateBearerToken(failureCall, "unexpected-jwt"))

        coVerify {
            errorCapture.capture(
                unexpectedFailure,
                failureCall,
                mapOf("auth.provider" to "bearer"),
            )
        }
    }

    @Test
    fun `malformed basic credentials are rejected`() = runTest {
        val invalidBase64 = mockCall("Basic not-base64!").first
        val missingColon = Base64.getEncoder().encodeToString("username-only".toByteArray())
        val missingColonCall = mockCall("Basic $missingColon").first

        middleware.authenticate(invalidBase64, null)
        middleware.authenticate(missingColonCall, null)

        coVerify { invalidBase64.respond(HttpStatusCode.Unauthorized, "") }
        coVerify { missingColonCall.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `basic credential security and unexpected failures are rejected and captured`() = runTest {
        val securityFailure = java.lang.SecurityException("denied")
        val unexpectedFailure = IllegalStateException("database unavailable")
        val encoded = Base64.getEncoder().encodeToString("user:password".toByteArray())
        val securityCall = mockCall("Basic $encoded").first
        val unexpectedCall = mockCall("Basic $encoded").first
        coEvery { securityService.authenticateWithCredential(any()) } throws securityFailure andThenThrows
            unexpectedFailure

        middleware.authenticate(securityCall, null)
        middleware.authenticate(unexpectedCall, null)

        coVerify { securityCall.respond(HttpStatusCode.Unauthorized, "") }
        coVerify { unexpectedCall.respond(HttpStatusCode.Unauthorized, "") }
        val captured = slot<Throwable>()
        coVerify {
            errorCapture.capture(
                capture(captured),
                unexpectedCall,
                mapOf("auth.provider" to "basic"),
            )
        }
        assertEquals(unexpectedFailure.message, captured.captured.message)
    }

    @Test
    fun `valid session cookie accepts quoted admin token`() = runTest {
        val jwt = mockk<DecodedJWT>()
        val (call, authContext) = mockCall(cookieHeader = "_bat=\"admin:::signed-session\"")
        every { securityConfiguration.verifier.verify("signed-session") } returns jwt
        coEvery { securityService.authenticateWithPayload(jwt) } returns testPrincipal

        middleware.authenticate(call, AuthConfig(emptyList(), optional = false))

        assertEquals(testPrincipal, authContext.principal("session"))
        verify { call.sessions.onLoad(Session("admin:::signed-session")) }
    }

    @Test
    fun `configured domain selects its cookie without inspecting the JWT`() = runTest {
        val prefix = AuthCookiePrefix("_bat_preview", listOf("preview.example"))
        val jwt = mockk<DecodedJWT>()
        val (call, authContext) = mockCall(
            cookieHeader = "_bat_preview=preview-token; _bat=default-token",
            appOrigin = "https://preview.example",
        )
        every { securityConfiguration.authCookiePrefixes } returns listOf(prefix)
        every { securityConfiguration.verifier.verify("preview-token") } returns jwt
        coEvery { securityService.authenticateWithPayload(jwt) } returns testPrincipal

        middleware.authenticate(call, AuthConfig(emptyList(), optional = false))

        assertEquals(testPrincipal, authContext.principal("session"))
        verify { call.sessions.onLoad(Session("preview-token")) }
    }

    @Test
    fun `configured domain ignores the default cookie when its cookie is absent`() = runTest {
        val prefix = AuthCookiePrefix("_bat_preview", listOf("preview.example"))
        val (call, authContext) = mockCall(
            cookieHeader = "_bat=default-token",
            appOrigin = "https://preview.example",
        )
        every { securityConfiguration.authCookiePrefixes } returns listOf(prefix)

        middleware.authenticate(call, AuthConfig(emptyList(), optional = true))

        assertNull(authContext.principal("session"))
        verify(exactly = 0) { securityConfiguration.verifier.verify(any<String>()) }
    }

    @Test
    fun `configured addressed domain does not fall back when application origin differs`() = runTest {
        val prefix = AuthCookiePrefix("_bat_preview", listOf("preview.example"))
        val jwt = mockk<DecodedJWT>()
        val verifier = securityConfiguration.verifier
        val (call, authContext) = mockCall(
            cookieHeader = "_bat_preview=preview-token; _bat=default-token",
            appOrigin = "https://studio.example",
            addressedHost = "preview.example",
        )
        every { securityConfiguration.authCookiePrefixes } returns listOf(prefix)
        every { verifier.verify("preview-token") } returns jwt
        coEvery { securityService.authenticateWithPayload(jwt) } returns testPrincipal

        middleware.authenticate(call, AuthConfig(emptyList(), optional = false))

        assertEquals(testPrincipal, authContext.principal("session"))
        verify(exactly = 1) { verifier.verify("preview-token") }
        verify(exactly = 0) { verifier.verify("default-token") }
    }

    @Test
    fun `configured addressed domain remains anonymous without its cookie when application origin differs`() = runTest {
        val prefix = AuthCookiePrefix("_bat_preview", listOf("preview.example"))
        val (call, authContext) = mockCall(
            cookieHeader = "_bat=default-token",
            appOrigin = "https://studio.example",
            addressedHost = "preview.example",
        )
        every { securityConfiguration.authCookiePrefixes } returns listOf(prefix)

        middleware.authenticate(call, AuthConfig(emptyList(), optional = true))

        assertNull(authContext.principal("session"))
        verify(exactly = 0) { securityConfiguration.verifier.verify(any<String>()) }
    }

    @Test
    fun `invalid session falls through to required route rejection`() = runTest {
        val (call, authContext) = mockCall(cookieHeader = "_bat=invalid-session")
        every {
            securityConfiguration.verifier.verify("invalid-session")
        } throws mockk<JWTVerificationException>()

        middleware.authenticate(call, AuthConfig(emptyList(), optional = false))

        assertNull(authContext.anyPrincipal())
        coVerify { call.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `unexpected session failure is captured while optional route remains anonymous`() = runTest {
        val failure = IllegalStateException("session lookup failed")
        val (call, authContext) = mockCall(cookieHeader = "_bat=session")
        every { securityConfiguration.verifier.verify("session") } throws failure

        middleware.authenticate(call, AuthConfig(emptyList(), optional = true))

        assertNull(authContext.anyPrincipal())
        coVerify {
            errorCapture.capture(
                failure,
                call,
                mapOf("auth.provider" to "session"),
            )
        }
        coVerify(exactly = 0) { call.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `unknown authorization schemes blank cookies and already authenticated calls fall through safely`() = runTest {
        val (unknownScheme, unknownContext) = mockCall(
            authorizationHeader = "Digest opaque",
            cookieHeader = "_bat=",
        )
        middleware.authenticate(unknownScheme, AuthConfig(emptyList(), optional = true))
        assertNull(unknownContext.anyPrincipal())
        coVerify(exactly = 0) { unknownScheme.respond(HttpStatusCode.Unauthorized, "") }

        val (noConfiguration, _) = mockCall()
        middleware.authenticate(noConfiguration, null)
        coVerify(exactly = 0) { noConfiguration.respond(HttpStatusCode.Unauthorized, "") }

        val (alreadyAuthenticated, authenticatedContext) = mockCall()
        authenticatedContext.principal("seed", testPrincipal)
        middleware.authenticate(alreadyAuthenticated, AuthConfig(emptyList(), optional = false))
        coVerify(exactly = 0) { alreadyAuthenticated.respond(HttpStatusCode.Unauthorized, "") }
    }

    @Test
    fun `session writer emits configured regular and admin cookies`() {
        every { securityConfiguration.domain } returns "app.example"
        every { securityConfiguration.adminDomain } returns "admin.example"
        every { securityConfiguration.cookieSecure } returns true
        every { securityConfiguration.cookieHttpOnly } returns true
        val responseCookies = ResponseCookies()
        val response = mockk<ServerResponse> {
            every { cookies } returns responseCookies
        }
        val call = mockk<ServerCall>()
        every { call.response } returns response
        every { call.request } returns mockk {
            every { origin } returns RequestOrigin("https", "app.example", 443)
            every { appOrigin } returns "https://app.example"
        }

        middleware.writeSession(call, Session("\"regular-token\""))
        middleware.writeSession(call, Session("admin:::admin-token"))
        middleware.writeSession(call, "ignored")

        assertEquals(2, responseCookies.cookies.size)
        assertEquals("regular-token", responseCookies.cookies[0].value)
        assertEquals("app.example", responseCookies.cookies[0].domain)
        assertEquals("admin-token", responseCookies.cookies[1].value)
        assertEquals("admin.example", responseCookies.cookies[1].domain)
        responseCookies.cookies.forEach {
            assertEquals(3600, it.maxAge)
            assertTrue(it.secure)
            assertTrue(it.httpOnly)
            assertEquals("Lax", it.sameSite)
        }
    }

    @Test
    fun `session writer uses the configured prefix as a host-only cookie`() {
        every { securityConfiguration.cookieSecure } returns true
        every { securityConfiguration.cookieHttpOnly } returns true
        every { securityConfiguration.authCookiePrefixes } returns
            listOf(AuthCookiePrefix("_bat_preview", listOf("preview.example")))
        val responseCookies = ResponseCookies()
        val response = mockk<ServerResponse> { every { cookies } returns responseCookies }
        val call = mockk<ServerCall> {
            every { this@mockk.response } returns response
            every { request } returns mockk {
                every { origin } returns RequestOrigin("https", "preview.example", 443)
                every { appOrigin } returns "https://preview.example"
            }
        }

        middleware.writeSession(call, Session("preview-token"))

        assertEquals(listOf("_bat_preview"), responseCookies.cookies.map { it.name })
        responseCookies.cookies.forEach { assertNull(it.domain) }
    }

    @Test
    fun `clearing a session covers every configured cookie domain`() {
        every { securityConfiguration.domain } returns "app.example"
        every { securityConfiguration.adminDomain } returns "admin.example"
        val responseCookies = ResponseCookies()
        val response = mockk<ServerResponse> {
            every { cookies } returns responseCookies
        }
        val call = mockk<ServerCall>()
        every { call.response } returns response
        every { call.request } returns mockk {
            every { origin } returns RequestOrigin("https", "app.example", 443)
            every { appOrigin } returns "https://app.example"
        }

        middleware.clearSession(call)

        assertEquals(
            listOf("app.example", "admin.example"),
            responseCookies.cookies.map { it.domain },
        )
        responseCookies.cookies.forEach {
            assertEquals("", it.value)
            assertEquals(0, it.maxAge)
        }
        every { securityConfiguration.adminDomain } returns "app.example"
        middleware.clearSession(call)

        assertEquals(listOf("app.example"), responseCookies.cookies.drop(2).map { it.domain })
    }
}
