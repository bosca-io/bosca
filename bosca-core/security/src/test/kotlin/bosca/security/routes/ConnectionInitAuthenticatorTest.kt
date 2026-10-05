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
import bosca.security.model.Principal
import bosca.security.service.ApiTokenScopes
import bosca.security.service.ApiTokenService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.auth.CallAuthenticationContext
import com.auth0.jwt.exceptions.JWTVerificationException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [ConnectionInitAuthenticator] — the graphql-transport-ws
 * `connection_init` auth path. Browsers can't set an Authorization
 * header on a WebSocket upgrade, so the studio passes its JWT as
 * `authToken` in the connection params; this authenticator validates
 * it through the same bearer flow as the header and attaches the
 * principal to the upgrade call. Without it, every authorization-gated
 * subscription executes anonymously and dies with a masked
 * "Subscription error".
 */
@OptIn(InternalDI::class, Internal::class)
class ConnectionInitAuthenticatorTest {

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
        scopes = listOf(ApiTokenScopes.CONTENT_VIEW.name),
        allowedGroupIds = null,
        credentialId = 1L,
    )

    private lateinit var authenticator: ConnectionInitAuthenticator

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

        authenticator = ConnectionInitAuthenticator(
            BoscaAuthMiddleware(
                securityConfiguration = securityConfiguration,
                connectionPool = connectionPool,
                securityService = securityService,
                apiTokenService = apiTokenService,
                tracer = tracer,
                cookieMaxAge = 3600L,
                errorCapture = errorCapture,
            ),
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
        io.mockk.unmockkAll()
    }

    private fun mockCall(): Pair<ServerCall, CallAuthenticationContext> {
        val authContext = CallAuthenticationContext()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.clientIp } returns "127.0.0.1"
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.authenticationContext } returns authContext
        return call to authContext
    }

    @Test
    fun `null payload is allowed through without authenticating`() = runTest {
        val (call, authContext) = mockCall()
        assertTrue(authenticator.authenticate(call, null))
        assertNull(authContext.anyPrincipal())
    }

    @Test
    fun `payload without authToken is allowed through without authenticating`() = runTest {
        val (call, authContext) = mockCall()
        val payload = JsonObject(mapOf("other" to JsonPrimitive("x")))
        assertTrue(authenticator.authenticate(call, payload))
        assertNull(authContext.anyPrincipal())
    }

    @Test
    fun `non-string authToken is treated as absent`() = runTest {
        val (call, authContext) = mockCall()
        val payload = JsonObject(mapOf("authToken" to JsonPrimitive(42)))
        assertTrue(authenticator.authenticate(call, payload))
        assertNull(authContext.anyPrincipal())
    }

    @Test
    fun `valid JWT authToken attaches the bearer principal to the upgrade call`() = runTest {
        val (call, authContext) = mockCall()
        coEvery { securityService.authenticateWithPayload(any()) } returns testPrincipal

        val payload = JsonObject(mapOf("authToken" to JsonPrimitive("a.jwt.token")))
        assertTrue(authenticator.authenticate(call, payload))
        assertEquals(testPrincipal, authContext.principal("bearer"))
    }

    @Test
    fun `invalid JWT authToken rejects the connection`() = runTest {
        val (call, authContext) = mockCall()
        every { securityConfiguration.verifier.verify(any<String>()) } throws JWTVerificationException("bad token")

        val payload = JsonObject(mapOf("authToken" to JsonPrimitive("a.bad.token")))
        assertFalse(authenticator.authenticate(call, payload))
        assertNull(authContext.anyPrincipal())
    }

    @Test
    fun `bsk_ authToken routes through the api token flow`() = runTest {
        val (call, authContext) = mockCall()
        coEvery { apiTokenService.authenticate("bsk_test_abc", "127.0.0.1") } returns testScopedPrincipal

        val payload = JsonObject(mapOf("authToken" to JsonPrimitive("bsk_test_abc")))
        assertTrue(authenticator.authenticate(call, payload))
        assertEquals(testScopedPrincipal, authContext.principal("api_token"))
        assertTrue(testScopedPrincipal.hasScope(ApiTokenScopes.CONTENT_VIEW.name))
        assertFalse(testScopedPrincipal.hasScope(ApiTokenScopes.CONTENT_EDIT.name))
    }

    @Test
    fun `failing bsk_ authToken rejects the connection without falling through to JWT`() = runTest {
        val (call, authContext) = mockCall()
        coEvery { apiTokenService.authenticate(any(), any()) } throws
            bosca.security.service.SecurityException("revoked")

        val payload = JsonObject(mapOf("authToken" to JsonPrimitive("bsk_revoked")))
        assertFalse(authenticator.authenticate(call, payload))
        assertNull(authContext.anyPrincipal())
        coEvery { securityService.authenticateWithPayload(any()) } throws AssertionError("JWT path must not run")
    }
}
