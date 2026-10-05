package bosca.gateway.routes

import bosca.gateway.configuration.GatewayProxyConfig
import bosca.gateway.model.Gateway
import bosca.gateway.model.GatewayAuthMethod
import bosca.gateway.model.GatewayConfig
import bosca.gateway.model.GatewayRoute
import bosca.gateway.service.GatewayConfigService
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests the REST `/api/v1/gateway/config` route the Rust proxy polls.
 *
 * Authentication is owned by the handler: a request whose
 * `Authorization: Bearer <token>` byte-equals the configured
 * [GatewayProxyConfig.sharedToken] is served; everything else is 401.
 * The framework's auth middleware is bypassed for this route (see
 * [GetGatewayConfig]) so the handler is the *only* place credentials
 * are evaluated.
 *
 * The cases below pin both the happy path, the rejection envelope,
 * and the `If-None-Match` short-circuit that prevents the endpoint
 * from being a DoS amplifier.
 */
class GetGatewayConfigTest {

    @BeforeTest
    fun setup() {
        route = GetGatewayConfig(
            configService = configService,
            proxyConfig = GatewayProxyConfig(sharedToken = SHARED_TOKEN),
        )
    }

    private val configService = mockk<GatewayConfigService>()
    private val authenticationContext = mockk<AuthenticationContext>()
    private val call = mockk<ServerCall>(relaxUnitFun = true)
    private val request = mockk<ServerRequest>()
    private val response = mockk<ServerResponse>(relaxUnitFun = true)

    private lateinit var route: GetGatewayConfig

    private val sampleConfig = GatewayConfig(
        version = "abc123",
        services = listOf(Gateway(id = UUID.random(), name = "trino", url = "http://trino")),
        routes = listOf(
            GatewayRoute(
                id = UUID.random(),
                gatewayId = UUID.random(),
                pathPattern = "/trino/**",
                authMethod = GatewayAuthMethod.JWT,
            ),
        ),
    )

    private fun stubCall(
        ifNoneMatch: String? = null,
        authorization: String? = null,
    ) {
        // Minimal ServerCall stand-in. The route only touches
        // request.headers["If-None-Match"], request.headers["Authorization"],
        // and response.header(...).
        val requestHeaders = mockk<bosca.server.RequestHeaders>()
        coEvery { requestHeaders["If-None-Match"] } returns ifNoneMatch
        coEvery { requestHeaders["Authorization"] } returns authorization
        coEvery { request.headers } returns requestHeaders
        coEvery { call.request } returns request
        coEvery { call.response } returns response
        // The 401 path goes through `call.respond(status, body)` which
        // reads `response.isCommitted` before writing — stub it as
        // not-yet-committed so the response can be sent.
        coEvery { response.isCommitted } returns false
    }

    // -----------------------------------------------------------------
    // Shared-token authentication
    // -----------------------------------------------------------------

    @Test
    fun `accepts a request whose bearer token matches the configured shared token`() = runTest {
        stubCall(authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"
        coEvery { configService.getConfig() } returns sampleConfig

        val result = route.execute(call, authenticationContext)

        assertNotNull(result)
        assertEquals("abc123", result.version)
    }

    @Test
    fun `accepts the bearer prefix case-insensitively`() = runTest {
        // RFC 7235 §2.1 token-and-credentials grammar treats the scheme
        // token case-insensitively. Some HTTP clients lowercase it.
        stubCall(authorization = "bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"
        coEvery { configService.getConfig() } returns sampleConfig

        val result = route.execute(call, authenticationContext)
        assertNotNull(result)
    }

    @Test
    fun `rejects with 401 when the shared token does not match`() = runTest {
        stubCall(authorization = "Bearer wrong-token")

        val result = route.execute(call, authenticationContext)

        assertNull(result)
        coVerify { response.respondText("", any(), HttpStatusCode.Unauthorized) }
        // No DB work allowed on the rejection path — a bad-token
        // request must not become a small DoS vector.
        coVerify(exactly = 0) { configService.getVersion() }
        coVerify(exactly = 0) { configService.getConfig() }
    }

    @Test
    fun `rejects with 401 when no Authorization header is present`() = runTest {
        stubCall(authorization = null)

        val result = route.execute(call, authenticationContext)

        assertNull(result)
        coVerify { response.respondText("", any(), HttpStatusCode.Unauthorized) }
    }

    @Test
    fun `rejects with 401 when Authorization uses a non-Bearer scheme`() = runTest {
        // A `Basic` header MUST NOT be silently treated as a candidate
        // for the shared-token comparison — that would let a Basic
        // credential whose plaintext happens to equal the token slip
        // through.
        stubCall(authorization = "Basic $SHARED_TOKEN")

        val result = route.execute(call, authenticationContext)

        assertNull(result)
        coVerify { response.respondText("", any(), HttpStatusCode.Unauthorized) }
    }

    @Test
    fun `rejects with 401 when sharedToken is null`() = runTest {
        // Deployments without a configured token make the route inert —
        // every request gets 401, even ones presenting some other
        // bearer token that might be valid elsewhere in the system.
        route = GetGatewayConfig(
            configService = configService,
            proxyConfig = GatewayProxyConfig(sharedToken = null),
        )
        stubCall(authorization = "Bearer any-token-at-all")

        val result = route.execute(call, authenticationContext)
        assertNull(result)
        coVerify { response.respondText("", any(), HttpStatusCode.Unauthorized) }
    }

    @Test
    fun `rejects with 401 when sharedToken is blank`() = runTest {
        // Blank is treated the same as unset — we never want a deployment
        // to accidentally accept `Authorization: Bearer ` as a credential.
        route = GetGatewayConfig(
            configService = configService,
            proxyConfig = GatewayProxyConfig(sharedToken = ""),
        )
        stubCall(authorization = "Bearer ")

        val result = route.execute(call, authenticationContext)
        assertNull(result)
        coVerify { response.respondText("", any(), HttpStatusCode.Unauthorized) }
    }

    // -----------------------------------------------------------------
    // ETag / conditional fetch
    // -----------------------------------------------------------------

    @Test
    fun `returns 304 when If-None-Match matches the current version`() = runTest {
        stubCall(ifNoneMatch = "abc123", authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"

        val result = route.execute(call, authenticationContext)

        // The route returns null after responding with 304 — the
        // framework treats the response as already-committed.
        assertNull(result)
        coVerify { response.header("ETag", "\"abc123\"") }
        coVerify { call.respond(HttpStatusCode.NotModified) }
        // The full config must NOT be loaded on the 304 path —
        // that's the whole point of `If-None-Match`.
        coVerify(exactly = 0) { configService.getConfig() }
    }

    @Test
    fun `handles quoted ETag values per RFC 7232`() = runTest {
        // Clients (including some HTTP libraries) wrap the ETag in
        // double quotes per RFC 7232. The route must accept that
        // form as equivalent to the bare value.
        stubCall(ifNoneMatch = "\"abc123\"", authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"

        val result = route.execute(call, authenticationContext)
        assertNull(result, "quoted If-None-Match should still match")
        coVerify { call.respond(HttpStatusCode.NotModified) }
    }

    @Test
    fun `returns the full config and sets ETag when If-None-Match does not match`() = runTest {
        stubCall(ifNoneMatch = "stale-version", authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"
        coEvery { configService.getConfig() } returns sampleConfig

        val result = route.execute(call, authenticationContext)

        assertNotNull(result)
        assertEquals("abc123", result.version)
        coVerify { response.header("ETag", "\"abc123\"") }
        coVerify { response.header("Cache-Control", any()) }
    }

    @Test
    fun `returns the full config when no If-None-Match is supplied`() = runTest {
        stubCall(ifNoneMatch = null, authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"
        coEvery { configService.getConfig() } returns sampleConfig

        val result = route.execute(call, authenticationContext)
        assertNotNull(result)
        assertEquals("abc123", result.version)
    }

    @Test
    fun `sets Cache-Control to no-store so intermediaries don't leak topology`() = runTest {
        stubCall(authorization = "Bearer $SHARED_TOKEN")
        coEvery { configService.getVersion() } returns "abc123"
        coEvery { configService.getConfig() } returns sampleConfig

        route.execute(call, authenticationContext)

        // The header value must explicitly forbid downstream caches —
        // version is the cache key and a stale cache could serve old
        // routing rules to the proxy.
        val cacheControl = slot<String>()
        coVerify { response.header("Cache-Control", capture(cacheControl)) }
        assertEquals(true, cacheControl.captured.contains("no-store"))
    }

    private companion object {
        private const val SHARED_TOKEN = "dev-gateway-proxy-shared-token-not-for-prod"
    }
}
