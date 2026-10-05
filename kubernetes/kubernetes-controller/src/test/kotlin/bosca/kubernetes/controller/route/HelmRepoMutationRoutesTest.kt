package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmIndexFetcher
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.repository.HelmRepo
import bosca.kubernetes.service.HelmRepoService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [HelmRepoAddRoute], [HelmRepoRefreshRoute], [HelmRepoRemoveRoute].
 * Coverage:
 *   * Admin gate on all three.
 *   * Add: name/url validation, `oci://` rejection, fetcher errors → 400.
 *   * Refresh: stale rows fall back to existing index when fetcher
 *     throws — the repo doesn't disappear from the studio.
 *   * Remove: missing name → 400; happy path delegates to the repository.
 */
class HelmRepoMutationRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val repos = mockk<HelmRepoService>(relaxed = true)
    private val fetcher = mockk<HelmIndexFetcher>()
    private val ctx = mockk<AuthenticationContext>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun callWithBody(body: String, path: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.contentType() } returns null
        coEvery { c.request.bodyText() } returns body
        every { c.application.json } returns Json { ignoreUnknownKeys = true; explicitNulls = false }
        return c
    }

    private fun pathOnly(path: Map<String, String>): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        return c
    }

    private val sampleRepo = K8sHelmRepo(name = "bitnami", url = "https://example.com", type = "http", charts = 5, lastUpdate = "now")

    @Test
    fun `add route refreshes the repo and returns the persisted view`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val row = HelmRepo("bitnami", "https://example.com", "http", indexYaml = "entries: {}")
        coEvery { fetcher.fetchIndex("https://example.com", null) } returns "entries: {}"
        coEvery { repos.save("bitnami", "https://example.com", "http", "entries: {}", null) } returns row
        every { fetcher.toRepoView(any(), any(), any(), any(), any(), any()) } returns sampleRepo
        val r = HelmRepoAddRoute(groups, repos, fetcher).runExecute(
            callWithBody("""{"name":"bitnami","url":"https://example.com"}"""), ctx,
        )
        assertEquals("bitnami", r?.name)
    }

    @Test
    fun `add route validates and persists supplied private repo credentials`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val row = HelmRepo("private", "https://example.com", "http", indexYaml = "entries: {}")
        coEvery {
            fetcher.fetchIndex(
                "https://example.com",
                match { it.username == "api_token" && it.password == "bsk_secret" },
            )
        } returns "entries: {}"
        coEvery {
            repos.save(
                "private", "https://example.com", "http", "entries: {}",
                match { it.username == "api_token" && it.password == "bsk_secret" },
            )
        } returns row
        every { fetcher.toRepoView(any(), any(), any(), any(), any(), any()) } returns sampleRepo.copy(name = "private")

        val result = HelmRepoAddRoute(groups, repos, fetcher).runExecute(
            callWithBody(
                """{"name":"private","url":"https://example.com","username":"api_token","password":"bsk_secret"}""",
            ),
            ctx,
        )

        assertEquals("private", result?.name)
    }

    @Test
    fun `add route rejects blank name`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = callWithBody("""{"name":"","url":"https://example.com"}""")
        assertNull(HelmRepoAddRoute(groups, repos, fetcher).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `add route rejects oci urls`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = callWithBody("""{"name":"x","url":"oci://example.io/charts"}""")
        assertNull(HelmRepoAddRoute(groups, repos, fetcher).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `add route maps fetcher errors to 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { fetcher.fetchIndex(any(), any()) } throws RuntimeException("404")
        val c = callWithBody("""{"name":"x","url":"https://gone.example"}""")
        assertNull(HelmRepoAddRoute(groups, repos, fetcher).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `refresh route refreshes every configured repo`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.list() } returns listOf(
            HelmRepo(name = "a", url = "https://a", type = "http"),
            HelmRepo(name = "b", url = "https://b", type = "http"),
        )
        coEvery { repos.credentials(any()) } returns null
        coEvery { fetcher.fetchIndex("https://a", null) } returns "entries: {}"
        coEvery { fetcher.fetchIndex("https://b", null) } returns "entries: {}"
        coEvery { repos.updateIndex("a", "entries: {}") } returns HelmRepo("a", "https://a", "http", indexYaml = "entries: {}")
        coEvery { repos.updateIndex("b", "entries: {}") } returns HelmRepo("b", "https://b", "http", indexYaml = "entries: {}")
        every { fetcher.toRepoView("a", any(), any(), any(), any(), any()) } returns sampleRepo.copy(name = "a")
        every { fetcher.toRepoView("b", any(), any(), any(), any(), any()) } returns sampleRepo.copy(name = "b")

        val r = HelmRepoRefreshRoute(groups, repos, fetcher).runExecute(callWithBody("{}"), ctx)
        assertEquals(setOf("a", "b"), r?.items?.map { it.name }?.toSet())
    }

    @Test
    fun `refresh route preserves stale rows when fetcher fails`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.list() } returns listOf(HelmRepo(name = "a", url = "https://a", type = "http"))
        coEvery { repos.credentials("a") } returns HelmRepoCredentials("user", "password")
        coEvery { fetcher.fetchIndex("https://a", any()) } throws RuntimeException("network")
        every { fetcher.toRepoView(any(), any(), any(), any(), any(), any()) } returns sampleRepo.copy(name = "a")

        val r = HelmRepoRefreshRoute(groups, repos, fetcher).runExecute(callWithBody("{}"), ctx)
        assertEquals(listOf("a"), r?.items?.map { it.name })
    }

    @Test
    fun `remove route deletes by name`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { repos.remove("bitnami") } returns Unit
        val r = HelmRepoRemoveRoute(groups, repos).runExecute(pathOnly(mapOf("name" to "bitnami")), ctx)
        assertEquals(true, r)
        coVerify { repos.remove("bitnami") }
    }

    @Test
    fun `remove route returns 400 when name path parameter is missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = pathOnly(emptyMap())
        assertNull(HelmRepoRemoveRoute(groups, repos).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected on every route`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        kotlin.test.assertFailsWith<SecurityException> {
            HelmRepoAddRoute(groups, repos, fetcher).runExecute(callWithBody("{}"), ctx)
        }
        kotlin.test.assertFailsWith<SecurityException> {
            HelmRepoRefreshRoute(groups, repos, fetcher).runExecute(callWithBody("{}"), ctx)
        }
        kotlin.test.assertFailsWith<SecurityException> {
            HelmRepoRemoveRoute(groups, repos).runExecute(pathOnly(mapOf("name" to "x")), ctx)
        }
        assertTrue(true)
    }
}
