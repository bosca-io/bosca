@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.transport

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.*
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryService
import bosca.routes.configureGitCiRoutes
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.routes.BoscaAuthMiddleware
import bosca.security.service.*
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.mockk.*
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.*

/** Real generated routes, Netty decoding, signed-cookie authentication and streaming; isolated domain fixtures. */
class ArtifactRoutesHttpTest {
    @Test fun `nested artifact names round trip through generated upload and download routes`() {
        for (name in listOf("build/reports/report #1 + café.zip", "bundle")) {
            val bytes = "artifact bytes for $name".toByteArray()
            assertEquals(204, request(name, bytes).statusCode(), authFailures.joinToString())
            val response = request(name)
            assertEquals(200, response.statusCode(), response.body().decodeToString())
            assertContentEquals(bytes, response.body())
            assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
            assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("attachment", response.headers().firstValue("Content-Disposition").orElse(""))
        }
    }

    @Test fun `artifact routes reject absent expired and invalid signed cookies`() {
        for (cookie in listOf(null, token(Instant.now().minusSeconds(60)), token(Instant.now().plusSeconds(3600), Algorithm.HMAC256("wrong-key")))) {
            assertEquals(401, request("bundle", cookie = cookie).statusCode())
            assertEquals(401, request("bundle", "unauthorized".toByteArray(), cookie = cookie).statusCode())
        }
    }

    @Test fun `repository permissions are checked and a run from another repository is rejected`() {
        for (bytes in listOf(null, "wrong repository".toByteArray())) {
            assertEquals(404, request("bundle", bytes, runId = foreignRunId).statusCode())
            val response = request("bundle", bytes, cookie = token(Instant.now().plusSeconds(3600), subject = deniedPrincipal.id.toString()))
            assertEquals(400, response.statusCode()) // Domain SecurityException follows Route's established mapping.
            assertTrue(response.body().decodeToString().contains("Permission denied"))
        }
        coVerify(atLeast = 1) { permissions.verifyAllowed(any(), repository, PermissionAction.VIEW) }
        coVerify(atLeast = 1) { permissions.verifyAllowed(any(), repository, PermissionAction.EDIT) }
    }

    private fun request(name: String, bytes: ByteArray? = null, cookie: String? = token(Instant.now().plusSeconds(3600)), runId: UUID = run.id): HttpResponse<ByteArray> {
        val encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ci/artifacts/${repository.id}/$runId/$encoded"))
        cookie?.let { builder.header("Cookie", "_ci_http=$it") }
        if (bytes == null) builder.GET() else builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {
        private val repository = Repository(id = UUID.random(), slug = "http-test", name = "HTTP test", ownerId = UUID.random(), visibility = Visibility.PRIVATE)
        private val run = PipelineRun(id = UUID.random(), pipelineId = UUID.random(), repositoryId = repository.id, commitSha = "abc", ref = "main", triggerType = PipelineTriggerType.MANUAL, number = 42)
        private val foreignRunId = UUID.random()
        private val principal = AuthenticatedPrincipal(Principal(id = UUID.random(), verified = true, anonymous = false), emptyList())
        private val deniedPrincipal = AuthenticatedPrincipal(Principal(id = UUID.random(), verified = true, anonymous = false), emptyList())
        private val jwtAlgorithm = Algorithm.HMAC256("isolated-ci-http-fixture-signing-key")
        private val artifacts = ConcurrentHashMap<String, ByteArray>()
        private val authFailures = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val permissions = mockk<RepositoryPermissionEvaluator>()
        private val client = HttpClient.newHttpClient()
        private var port = 0
        private lateinit var engine: NettyServerEngine

        private fun token(expiration: Instant, signing: Algorithm = jwtAlgorithm, subject: String = principal.id.toString()): String = JWT.create()
            .withIssuer("ci-http").withAudience("ci-http").withSubject(subject).withExpiresAt(Date.from(expiration)).sign(signing)

        @JvmStatic @BeforeClass fun start() {
            ProviderRegistry.clear()
            val pool = mockk<ConnectionPool>(relaxed = true)
            provides<ConnectionPool>(singleton = true) { pool }
            provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
            provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
            provides<AuthenticationProviders>(singleton = true) { AuthenticationProviders(arrayOf("session")) }
            val repositories = mockk<RepositoryService>()
            coEvery { repositories.findById(repository.id) } returns repository
            val runs = mockk<PipelineRunService>()
            coEvery { runs.findById(run.id) } returns run
            coEvery { runs.findById(foreignRunId) } returns run.copy(id = foreignRunId, repositoryId = UUID.random())
            coEvery { permissions.verifyAllowed(any(), repository, any()) } coAnswers {
                if (firstArg<AuthenticationContext>().principal()?.id != principal.id) throw SecurityException("Permission denied")
            }
            val artifactService = mockk<PipelineArtifactService>()
            coEvery { artifactService.upload(repository.id, run.id, run.number, any(), any(), any()) } answers {
                artifacts[arg<String>(3)] = arg<java.io.InputStream>(4).readBytes()
            }
            coEvery { artifactService.download(repository.id, run.number, any()) } answers { artifacts[arg<String>(2)]?.inputStream() }
            provides<ArtifactUploadRoute>(singleton = true) { ArtifactUploadRoute(artifactService, repositories, runs, permissions) }
            provides<ArtifactDownloadRoute>(singleton = true) { ArtifactDownloadRoute(artifactService, repositories, runs, permissions) }
            val security = mockk<SecurityService>()
            coEvery { security.authenticateWithPayload(any()) } answers {
                when (firstArg<com.auth0.jwt.interfaces.Payload>().subject) {
                    principal.id.toString() -> principal
                    deniedPrincipal.id.toString() -> deniedPrincipal
                    else -> throw SecurityException("Unknown principal")
                }
            }
            val auth = BoscaAuthMiddleware(
                securityConfiguration = mockk<SecurityConfiguration>(relaxed = true) {
                    every { verifier } returns JWT.require(jwtAlgorithm).withIssuer("ci-http").withAudience("ci-http").build()
                    every { authCookiePrefixes } returns listOf(AuthCookiePrefix("_ci_http", listOf("127.0.0.1")))
                }, connectionPool = pool, securityService = security, apiTokenService = mockk(relaxed = true),
                tracer = OpenTelemetry.noop().getTracer("ci-http"), cookieMaxAge = 3600,
                errorCapture = bosca.observability.ErrorCapture { throwable, _, _ -> authFailures.add(throwable.toString()) },
            )
            port = ServerSocket(0).use { it.localPort }
            val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    worker-threads: 2\n    codec-threads: 1\n".byteInputStream()))
            app.installAuth(auth)
            runBlocking { app.configureGitCiRoutes() }
            engine = NettyServerEngine(app, port)
            // stop() halts the JVM; this isolated daemon server expires with the test worker.
            thread(isDaemon = true, name = "ci-artifact-http") { engine.start() }
            repeat(100) {
                try { Socket("127.0.0.1", port).use { return } } catch (_: java.io.IOException) { Thread.sleep(50) }
            }
            error("CI artifact HTTP test server did not start")
        }

        @JvmStatic @AfterClass fun cleanUp() { ProviderRegistry.clear(); artifacts.clear() }
    }
}
