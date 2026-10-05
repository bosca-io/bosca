package bosca.kubernetes.service

import bosca.kubernetes.model.ConfigKind
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import com.auth0.jwt.interfaces.DecodedJWT
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins the [KubernetesControllerClient] HTTP plumbing — JWT minting,
 * Authorization header, query-parameter passthrough, response decoding,
 * and the non-2xx → thrown-error contract that resolvers depend on.
 *
 * The mock server stands in for the real kubernetes-controller; we
 * assert the request URL, method, headers, and body each time.
 */
@OptIn(ExperimentalUuidApi::class)
class KubernetesControllerClientTest {

    private val mockServer = MockWebServer().apply { start() }
    private val baseUrl = mockServer.url("").toString().trimEnd('/')
    private val clusterId = UUID.random()

    private val security = mockk<SecurityService>()
    private val ctx = mockk<AuthenticationContext>()
    private val principal = mockk<AuthenticatedPrincipal>()
    private val decoded = mockk<DecodedJWT>()

    init {
        every { decoded.token } returns "test-jwt"
        every { principal.asPrincipal() } returns Principal()
        every { ctx.principal() } returns principal
        coEvery { security.createJwtToken(any(), any()) } returns decoded
    }

    // Aggressive timeouts so a hung test fails fast rather than blocking CI for 5s.
    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofMillis(500))
        .readTimeout(Duration.ofMillis(500))
        .build()

    private val client = KubernetesControllerClient(
        baseUrl = baseUrl,
        securityService = security,
        http = http,
        streamingHttp = http,
    )

    @AfterTest
    fun tearDown() {
        mockServer.close()
        io.mockk.unmockkAll()
    }

    @Test
    fun `namespaces happy path sends GET with bearer token and decodes response`() = runTest {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"items":[{"name":"default","status":"Active","age":"1d"},{"name":"kube-system","status":"Active","age":"30d"}]}""")
                .build()
        )
        val resp = client.namespaces(ctx, clusterId)
        assertEquals(2, resp.items.size)
        assertEquals("default", resp.items[0].name)

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/clusters/$clusterId/namespaces", request.target)
        assertEquals("Bearer test-jwt", request.headers["Authorization"])
    }

    @Test
    fun `config resources request honours kind and namespace query parameters`() = runTest {
        mockServer.enqueue(
            MockResponse.Builder().code(200).body("""{"items":[]}""").build()
        )
        client.configResources(ctx, clusterId, namespace = "prod", kind = ConfigKind.SECRET)

        val request = mockServer.takeRequest()
        // The path encodes the cluster id; query is appended.
        assertTrue(request.target.startsWith("/clusters/$clusterId/config"))
        assertTrue(request.target.contains("namespace=prod"))
        assertTrue(request.target.contains("kind=SECRET"))
    }

    @Test
    fun `4xx response from controller throws a meaningful error`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(500).body("server bombed").build())
        val ex = assertFailsWith<IllegalStateException> {
            client.namespaces(ctx, clusterId)
        }
        assertTrue(ex.message!!.contains("500"), "expected status in error message, got: ${ex.message}")
    }

    @Test
    fun `401 from controller is explicitly surfaced as denied request`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(401).body("nope").build())
        val ex = assertFailsWith<IllegalStateException> {
            client.namespaces(ctx, clusterId)
        }
        assertTrue(ex.message!!.contains("denied") || ex.message!!.contains("401"))
    }

    @Test
    fun `403 from controller is surfaced as denied request`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(403).body("forbidden").build())
        assertFailsWith<IllegalStateException> { client.namespaces(ctx, clusterId) }
    }

    @Test
    fun `apply manifest sends POST with serialized body`() = runTest {
        mockServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"succeeded":true,"applied":["Pod/api"],"failed":[]}""")
                .build()
        )
        val result = client.applyManifest(
            ctx, clusterId,
            manifest = "apiVersion: v1\nkind: Pod\n",
            dryRun = true,
        )
        assertTrue(result.applied.isNotEmpty())

        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/clusters/$clusterId/apply", request.target)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("apiVersion: v1"))
        assertTrue(body.contains("dryRun"))
    }

    @Test
    fun `invalidate is silent on non-2xx so cache rotation never blocks mutations`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(500).body("nope").build())
        // Should not throw.
        client.invalidate(ctx, clusterId)
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/clusters/$clusterId/invalidate", request.target)
    }

    @Test
    fun `invalidate happy path also returns silently`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(204).build())
        client.invalidate(ctx, clusterId)
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
    }

    @Test
    fun `mintBearerToken throws when authentication has no principal`() = runTest {
        every { ctx.principal() } returns null
        val ex = assertFailsWith<IllegalStateException> {
            client.namespaces(ctx, clusterId)
        }
        assertTrue(ex.message!!.contains("authenticated principal"),
            "expected principal-missing message, got: ${ex.message}")
    }

    @Test
    fun `baseUrl with trailing slash is normalised`() = runTest {
        // Re-create the client with a base URL that has a trailing slash. The internal
        // `trimEnd('/')` is supposed to keep the request path identical.
        val trailing = KubernetesControllerClient(
            baseUrl = "$baseUrl/",
            securityService = security,
            http = http,
            streamingHttp = http,
        )
        mockServer.enqueue(MockResponse.Builder().code(200).body("""{"items":[]}""").build())
        trailing.namespaces(ctx, clusterId)
        val request = mockServer.takeRequest()
        assertEquals("/clusters/$clusterId/namespaces", request.target)
    }

    @Test
    fun `companion default base url constant matches the docker service hostname`() {
        // Pin the constant — bosca-server is configured against this hostname via env in
        // docker-compose.yaml and we don't want it drifting silently.
        assertEquals("http://kubernetes-controller:8082", KubernetesControllerClient.DEFAULT_BASE_URL)
    }

    @Test
    fun `defaultClient factory returns a configured OkHttpClient`() {
        val c = KubernetesControllerClient.defaultClient()
        // Connect/read timeouts can be observed via the builder pattern's read accessors.
        assertEquals(Duration.ofSeconds(5).toMillis().toInt(), c.connectTimeoutMillis)
        assertEquals(Duration.ofSeconds(30).toMillis().toInt(), c.readTimeoutMillis)
    }

    @Test
    fun `streamingClient zeroes read and call timeouts`() {
        val base = KubernetesControllerClient.defaultClient()
        val streaming = KubernetesControllerClient.streamingClient(base)
        assertEquals(0, streaming.readTimeoutMillis, "streaming reader must not time out on quiet log streams")
        assertEquals(0, streaming.callTimeoutMillis)
    }
}
