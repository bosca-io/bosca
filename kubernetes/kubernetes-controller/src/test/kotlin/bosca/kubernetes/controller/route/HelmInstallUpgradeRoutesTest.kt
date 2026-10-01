package bosca.kubernetes.controller.route

import bosca.db.withConnectionManager
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.repository.HelmRepo
import bosca.kubernetes.service.HelmRepoService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [HelmInstallRoute] and [HelmUpgradeRoute]. Both routes resolve
 * the Bosca-side repo URL via `withConnectionManager { repos.getByName(...) }`
 * — a top-level suspend function that owns a real Postgres connection.
 * We mock it with [mockkStatic] so the test never reaches the DB; the
 * suspend block runs inline against the mocked [HelmRepoService].
 */
@OptIn(ExperimentalUuidApi::class)
class HelmInstallUpgradeRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val helm = mockk<HelmRuntime>()
    private val repos = mockk<HelmRepoService>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionPoolKt")
        coEvery { withConnectionManager<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionPoolKt")
        io.mockk.unmockkAll()
    }

    private fun callWithBody(id: UUID, body: String, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to id.toString()))
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        every { c.request.contentType() } returns null
        coEvery { c.request.bodyText() } returns body
        every { c.application.json } returns Json { ignoreUnknownKeys = true; explicitNulls = false }
        return c
    }

    private fun release(name: String, ns: String, revision: Int) = K8sHelmRelease(
        id = "$ns/$name/$revision", name = name, namespace = ns,
        chart = "nginx", chartVersion = "1.0.$revision", appVersion = "1.27.0",
        revision = revision, status = HelmStatus.DEPLOYED, updated = "now", installed = "1d",
        repo = "", repoUrl = "", description = "",
    )

    private fun repo(name: String, url: String) =
        HelmRepo(name = name, url = url, type = "http", indexYaml = null)

    // ---------- install ----------

    @Test
    fun `install resolves repo URL and forwards to HelmRuntime`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("bitnami") } returns repo("bitnami", "https://charts.bitnami.com")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery { helm.install(client, id, any(), "https://charts.bitnami.com", null) } returns release("api", "prod", 1)

        val r = HelmInstallRoute(groups, pool, helm, repos).runExecute(
            callWithBody(id, """{"name":"api","namespace":"prod","repo":"bitnami","chart":"nginx","version":"1.0.0"}"""),
            ctx,
        )
        assertEquals("api", r?.name)
    }

    @Test
    fun `install forwards decrypted private repository credentials`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val secret = HelmRepoCredentials("api_token", "bsk_secret")
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("private") } returns repo("private", "https://charts.example.com")
        coEvery { repos.credentials("private") } returns secret
        coEvery { helm.install(client, id, any(), "https://charts.example.com", secret) } returns release("api", "prod", 1)

        val result = HelmInstallRoute(groups, pool, helm, repos).runExecute(
            callWithBody(id, """{"name":"api","namespace":"prod","repo":"private","chart":"nginx","version":"1.0.0"}"""),
            ctx,
        )

        assertEquals("api", result?.name)
    }

    @Test
    fun `install rejects blank required fields with 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = callWithBody(id, """{"name":"","namespace":"prod","repo":"bitnami","chart":"nginx","version":"1.0.0"}""")
        assertNull(HelmInstallRoute(groups, pool, helm, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `install 400s when repo is not registered in Bosca`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("missing") } returns null

        val c = callWithBody(id, """{"name":"api","namespace":"prod","repo":"missing","chart":"nginx","version":"1.0.0"}""")
        assertNull(HelmInstallRoute(groups, pool, helm, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `install propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("bitnami") } returns repo("bitnami", "https://charts.bitnami.com")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery { helm.install(client, id, any(), any(), any()) } throws RuntimeException("rendering failed")
        assertFailsWith<RuntimeException> {
            HelmInstallRoute(groups, pool, helm, repos).runExecute(
                callWithBody(id, """{"name":"api","namespace":"prod","repo":"bitnami","chart":"nginx","version":"1.0.0"}"""),
                ctx,
            )
        }
    }

    @Test
    fun `install rejected for non-admin caller`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            HelmInstallRoute(groups, pool, helm, repos).runExecute(
                callWithBody(UUID.random(), """{"name":"x","namespace":"y","repo":"r","chart":"c","version":"v"}"""),
                ctx,
            )
        }
    }

    // ---------- upgrade ----------

    @Test
    fun `upgrade requires repo and chart query parameters`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = callWithBody(id, """{"name":"api","namespace":"prod","version":"1.1.0"}""")
        assertNull(HelmUpgradeRoute(groups, pool, helm, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `upgrade resolves repo URL and forwards to HelmRuntime`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("bitnami") } returns repo("bitnami", "https://charts.bitnami.com")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery {
            helm.upgrade(client, id, any(), "https://charts.bitnami.com", "nginx", null)
        } returns release("api", "prod", 2)

        val r = HelmUpgradeRoute(groups, pool, helm, repos).runExecute(
            callWithBody(
                id,
                """{"name":"api","namespace":"prod","version":"1.1.0"}""",
                mapOf("repo" to "bitnami", "chart" to "nginx"),
            ),
            ctx,
        )
        assertEquals(2, r?.revision)
    }

    @Test
    fun `upgrade rejects blank name or version`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = callWithBody(
            id,
            """{"name":"","namespace":"prod","version":"1.1.0"}""",
            mapOf("repo" to "bitnami", "chart" to "nginx"),
        )
        assertNull(HelmUpgradeRoute(groups, pool, helm, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `upgrade 400s when repo lookup misses`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("missing") } returns null

        val c = callWithBody(
            id,
            """{"name":"api","namespace":"prod","version":"1.1.0"}""",
            mapOf("repo" to "missing", "chart" to "nginx"),
        )
        assertNull(HelmUpgradeRoute(groups, pool, helm, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `upgrade propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { repos.getByName("bitnami") } returns repo("bitnami", "https://charts.bitnami.com")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery { helm.upgrade(client, id, any(), any(), any(), any()) } throws RuntimeException("conflict")
        assertFailsWith<RuntimeException> {
            HelmUpgradeRoute(groups, pool, helm, repos).runExecute(
                callWithBody(
                    id,
                    """{"name":"api","namespace":"prod","version":"1.1.0"}""",
                    mapOf("repo" to "bitnami", "chart" to "nginx"),
                ),
                ctx,
            )
        }
    }
}
