package bosca.git.transport

import bosca.git.model.CommitStatusState
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.RepositoryService
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamCityWebhookRouteTest {

    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val route = TeamCityWebhookRoute(commitStatusService, repositoryService, permissionEvaluator)

    private val repositoryId = UUID.random()
    private val repository = Repository(id = repositoryId, slug = "r", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE)

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        coEvery { repositoryService.findById(repositoryId) } returns repository
    }

    @AfterTest
    fun teardown() = clearRouteProviders()

    private fun payload(sha: String? = "a".repeat(40), state: String = "finished", status: String? = "SUCCESS"): String {
        val revisions = if (sha != null) ""","revisions":{"revision":[{"version":"$sha"}]}""" else ""
        val statusField = if (status != null) """"status":"$status",""" else ""
        return """{"build":{$statusField"state":"$state","buildTypeId":"Bosca_Build","statusText":"ok","webUrl":"https://tc/b/1"$revisions}}"""
    }

    @Test
    fun `malformed json returns 400`() = runTest {
        val (call, rec) = recordedCall(body = "not json".toByteArray())
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)
    }

    @Test
    fun `payload without a build field returns 400`() = runTest {
        val (call, rec) = recordedCall(body = """{"x":1}""".toByteArray())
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)
    }

    @Test
    fun `payload without a revision is acknowledged and skipped`() = runTest {
        val (call, rec) = recordedCall(body = payload(sha = null).toByteArray())
        runRoute(route, call)
        assertEquals(HttpStatusCode.OK, rec.status)
        coVerify(exactly = 0) { commitStatusService.recordStatus(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing or invalid repositoryId returns 400`() = runTest {
        recordedCall(body = payload().toByteArray()).let { (call, rec) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, rec.status)
        }
        recordedCall(queryParameters = mapOf("repositoryId" to "not-a-uuid"), body = payload().toByteArray()).let { (call, rec) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, rec.status)
        }
    }

    @Test
    fun `unknown repository returns 404`() = runTest {
        val missing = UUID.random()
        coEvery { repositoryService.findById(missing) } returns null
        val (call, rec) = recordedCall(queryParameters = mapOf("repositoryId" to missing.toString()), body = payload().toByteArray())
        runRoute(route, call)
        assertEquals(HttpStatusCode.NotFound, rec.status)
    }

    @Test
    fun `successful build records a commit status`() = runTest {
        val sha = "b".repeat(40)
        val (call, rec) = recordedCall(
            queryParameters = mapOf("repositoryId" to repositoryId.toString()),
            body = payload(sha = sha).toByteArray(),
        )
        runRoute(route, call)

        assertEquals(HttpStatusCode.OK, rec.status)
        coVerify {
            commitStatusService.recordStatus(
                repositoryId, sha, "teamcity/Bosca_Build", CommitStatusState.SUCCESS, "ok", "https://tc/b/1",
            )
        }
    }

    @Test
    fun `running build translates to PENDING`() {
        assertEquals(
            CommitStatusState.PENDING,
            TeamCityWebhookRoute.translateBuildState("running", null)
        )
    }

    @Test
    fun `queued build translates to PENDING`() {
        assertEquals(
            CommitStatusState.PENDING,
            TeamCityWebhookRoute.translateBuildState("queued", null)
        )
    }

    @Test
    fun `SUCCESS status translates to SUCCESS`() {
        assertEquals(
            CommitStatusState.SUCCESS,
            TeamCityWebhookRoute.translateBuildState("finished", "SUCCESS")
        )
    }

    @Test
    fun `FAILURE status translates to FAILURE`() {
        assertEquals(
            CommitStatusState.FAILURE,
            TeamCityWebhookRoute.translateBuildState("finished", "FAILURE")
        )
    }

    @Test
    fun `ERROR status translates to ERROR`() {
        assertEquals(
            CommitStatusState.ERROR,
            TeamCityWebhookRoute.translateBuildState("finished", "ERROR")
        )
    }

    @Test
    fun `finished with no status translates to SUCCESS`() {
        assertEquals(
            CommitStatusState.SUCCESS,
            TeamCityWebhookRoute.translateBuildState("finished", null)
        )
    }

    @Test
    fun `minimal running build with no status fields records PENDING`() = runTest {
        val sha = "c".repeat(40)
        val body = """{"build":{"state":"running","revisions":{"revision":[{"version":"$sha"}]}}}"""
        val (call, rec) = recordedCall(
            queryParameters = mapOf("repositoryId" to repositoryId.toString()),
            body = body.toByteArray(),
        )
        runRoute(route, call)

        assertEquals(HttpStatusCode.OK, rec.status)
        coVerify {
            commitStatusService.recordStatus(
                repositoryId, sha, "teamcity/unknown", CommitStatusState.PENDING, null, null,
            )
        }
    }

    @Test
    fun `empty revision list is acknowledged and skipped`() = runTest {
        val body = """{"build":{"state":"finished","status":"SUCCESS","revisions":{"revision":[]}}}"""
        val (call, rec) = recordedCall(
            queryParameters = mapOf("repositoryId" to repositoryId.toString()),
            body = body.toByteArray(),
        )
        runRoute(route, call)
        assertEquals(HttpStatusCode.OK, rec.status)
        coVerify(exactly = 0) { commitStatusService.recordStatus(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown state and status default to PENDING`() {
        assertEquals(CommitStatusState.PENDING, TeamCityWebhookRoute.translateBuildState("weird", "OTHER"))
        assertEquals(CommitStatusState.PENDING, TeamCityWebhookRoute.translateBuildState(null, null))
    }
}
