package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmIndexFetcher
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.repository.HelmRepo
import bosca.kubernetes.service.HelmRepoService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [HelmReposRoute], [HelmChartsRoute], [HelmChartVersionsRoute],
 * [HelmChartValuesRoute]. The catalog routes read from the in-Bosca
 * Helm repo registry — not from a target cluster — so they have no
 * `{id}` cluster parameter. Coverage:
 *
 *  * Admin gate on each.
 *  * Real `index.yaml` parsing via [bosca.kubernetes.controller.helm.HelmIndex].
 *  * Repo and chart-version filters.
 *  * `NotFound` for unknown repo / chart / version.
 *  * `HelmChartValuesRoute` falls back to an empty values doc when the
 *    underlying chart fetch fails.
 */
class HelmCatalogRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val repos = mockk<HelmRepoService>()
    private val fetcher = mockk<HelmIndexFetcher>()
    private val ctx = mockk<AuthenticationContext>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private val sampleIndex = """
        apiVersion: v1
        entries:
          nginx:
            - apiVersion: v2
              name: nginx
              version: 1.2.3
              appVersion: 1.27.0
              description: NGINX webserver
              urls: ["https://example.com/nginx-1.2.3.tgz"]
              created: "2026-05-15T03:21:00Z"
            - apiVersion: v2
              name: nginx
              version: 1.2.2
              appVersion: 1.26.0
              description: NGINX webserver
              urls: ["https://example.com/nginx-1.2.2.tgz"]
              created: "2026-05-14T03:21:00Z"
          postgres:
            - apiVersion: v2
              name: postgres
              version: 16.4.0
              appVersion: "16.4"
              description: PostgreSQL database
              urls: ["https://example.com/postgres-16.4.0.tgz"]
              created: "2026-05-10T03:21:00Z"
    """.trimIndent()

    private fun repo(name: String, url: String = "https://charts.example.com", index: String? = sampleIndex) =
        HelmRepo(name = name, url = url, type = "http", indexYaml = index)

    @Test
    fun `repos route maps each stored row through fetcher`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.list() } returns listOf(repo("bitnami"), repo("jetstack"))
        every { fetcher.toRepoView(any(), any(), any(), any(), any(), any()) } answers {
            bosca.kubernetes.model.K8sHelmRepo(
                name = firstArg(),
                url = arg(1),
                type = arg(2),
                charts = 0,
                lastUpdate = "-",
            )
        }

        val r = HelmReposRoute(groups, repos, fetcher).runExecute(call(), ctx)
        assertEquals(listOf("bitnami", "jetstack"), r?.items?.map { it.name })
    }

    @Test
    fun `charts route walks all repos when no repo filter`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.list() } returns listOf(repo("bitnami"))
        val r = HelmChartsRoute(groups, repos).runExecute(call(), ctx)
        assertEquals(setOf("nginx", "postgres"), r?.items?.map { it.name }?.toSet())
    }

    @Test
    fun `charts route narrows to repo and applies search filter`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("bitnami") } returns repo("bitnami")
        val r = HelmChartsRoute(groups, repos).runExecute(
            call(query = mapOf("repo" to "bitnami", "search" to "postgres")), ctx,
        )
        assertEquals(listOf("postgres"), r?.items?.map { it.name })
    }

    @Test
    fun `charts route returns empty when the repo filter does not match`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("missing") } returns null
        val r = HelmChartsRoute(groups, repos).runExecute(call(query = mapOf("repo" to "missing")), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `versions route returns every published version of a chart`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("bitnami") } returns repo("bitnami")
        val r = HelmChartVersionsRoute(groups, repos).runExecute(
            call(path = mapOf("repo" to "bitnami", "chart" to "nginx")), ctx,
        )
        assertEquals(listOf("1.2.3", "1.2.2"), r?.items?.map { it.version })
        assertTrue(r?.items?.first()?.current ?: false)
    }

    @Test
    fun `versions route returns 400 when repo path parameter is missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(path = mapOf("chart" to "nginx"))
        assertNull(HelmChartVersionsRoute(groups, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `versions route returns 404 when repo is unknown`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("missing") } returns null
        val c = call(path = mapOf("repo" to "missing", "chart" to "nginx"))
        assertNull(HelmChartVersionsRoute(groups, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.NotFound) }
    }

    @Test
    fun `values route resolves the tarball URL and returns fetched values`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("bitnami") } returns repo("bitnami")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery {
            fetcher.fetchValues(
                "https://example.com/nginx-1.2.3.tgz",
                "https://charts.example.com",
                null,
            )
        } returns
            K8sHelmChartValues(defaultValues = "replicas: 3\n", schema = null)
        val r = HelmChartValuesRoute(groups, repos, fetcher).runExecute(
            call(path = mapOf("repo" to "bitnami", "chart" to "nginx", "version" to "1.2.3")), ctx,
        )
        assertEquals("replicas: 3\n", r?.defaultValues)
    }

    @Test
    fun `values route decrypts and forwards private repository credentials`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val secret = HelmRepoCredentials("api_token", "bsk_secret")
        coEvery { repos.getByName("private") } returns repo("private")
        coEvery { repos.credentials("private") } returns secret
        coEvery {
            fetcher.fetchValues(
                "https://example.com/nginx-1.2.3.tgz",
                "https://charts.example.com",
                secret,
            )
        } returns K8sHelmChartValues(defaultValues = "private: true\n")

        val result = HelmChartValuesRoute(groups, repos, fetcher).runExecute(
            call(path = mapOf("repo" to "private", "chart" to "nginx", "version" to "1.2.3")),
            ctx,
        )

        assertEquals("private: true\n", result?.defaultValues)
    }

    @Test
    fun `values route returns empty values when fetcher throws`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("bitnami") } returns repo("bitnami")
        coEvery { repos.credentials("bitnami") } returns null
        coEvery { fetcher.fetchValues(any(), any(), any()) } throws RuntimeException("network")
        val r = HelmChartValuesRoute(groups, repos, fetcher).runExecute(
            call(path = mapOf("repo" to "bitnami", "chart" to "nginx", "version" to "1.2.3")), ctx,
        )
        assertEquals("", r?.defaultValues)
    }

    @Test
    fun `values route returns 404 when chart version is unknown`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.getByName("bitnami") } returns repo("bitnami")
        val c = call(path = mapOf("repo" to "bitnami", "chart" to "nginx", "version" to "9.9.9"))
        assertNull(HelmChartValuesRoute(groups, repos, fetcher).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.NotFound) }
    }

    @Test
    fun `values route returns 400 when path parameters are missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(path = mapOf("repo" to "bitnami", "chart" to "nginx"))
        assertNull(HelmChartValuesRoute(groups, repos, fetcher).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected on each route`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> { HelmReposRoute(groups, repos, fetcher).runExecute(call(), ctx) }
        assertFailsWith<SecurityException> { HelmChartsRoute(groups, repos).runExecute(call(), ctx) }
        assertFailsWith<SecurityException> { HelmChartVersionsRoute(groups, repos).runExecute(call(), ctx) }
        assertFailsWith<SecurityException> { HelmChartValuesRoute(groups, repos, fetcher).runExecute(call(), ctx) }
    }
}
