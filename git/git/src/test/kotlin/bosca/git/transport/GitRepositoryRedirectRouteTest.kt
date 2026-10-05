package bosca.git.transport

import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.service.RepositoryService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers repository clone-URL redirects, parameter validation, and missing configuration. */
class GitRepositoryRedirectRouteTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val repository = Repository(
        id = UUID.random(),
        slug = "repo",
        name = "Repository",
        ownerId = UUID.random(),
        visibility = Visibility.PRIVATE,
    )

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        coEvery { repositoryService.findByOwnerAndSlug("owner", "repo") } returns repository
    }

    @AfterTest
    fun teardown() {
        clearRouteProviders()
    }

    @Test
    fun `repository URL without git suffix redirects to its Studio page`() = runTest {
        val route = route("app:\n  url: https://studio.example.com/\n")
        val (call, response) = recordedCall(pathParameters = params())

        runRoute(route, call)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://studio.example.com/git/repositories/${repository.id}", response.headers["Location"])
    }

    @Test
    fun `repository clone URL with git suffix redirects to its Studio page`() = runTest {
        val route = route("app:\n  url: https://studio.example.com/\n")
        val (call, response) = recordedCall(pathParameters = params(repo = "repo.git"))

        runRoute(route, call)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://studio.example.com/git/repositories/${repository.id}", response.headers["Location"])
    }

    @Test
    fun `missing path parameters return 400`() = runTest {
        val route = route("app:\n  url: https://studio.example.com\n")

        recordedCall(pathParameters = params(owner = null)).let { (call, response) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        recordedCall(pathParameters = params(repo = null)).let { (call, response) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `unknown repository returns 404`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("owner", "missing") } returns null
        val route = route("app:\n  url: https://studio.example.com\n")
        val (call, response) = recordedCall(pathParameters = params(repo = "missing"))

        runRoute(route, call)

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `missing or blank Studio URL returns 503`() = runTest {
        val missingRoute = route("other: value\n")
        val (missingCall, missingResponse) = recordedCall(pathParameters = params())
        runRoute(missingRoute, missingCall)
        assertEquals(HttpStatusCode.ServiceUnavailable, missingResponse.status)

        val blankRoute = route("app:\n  url: '   '\n")
        val (blankCall, blankResponse) = recordedCall(pathParameters = params())
        runRoute(blankRoute, blankCall)
        assertEquals(HttpStatusCode.ServiceUnavailable, blankResponse.status)
    }

    private fun route(yaml: String): GitRepositoryRedirectRoute {
        val config = ApplicationConfig.load(yaml.byteInputStream())
        return GitRepositoryRedirectRoute(repositoryService, BoscaApplication(config))
    }

    private fun params(owner: String? = "owner", repo: String? = "repo") = buildMap {
        if (owner != null) put("owner", owner)
        if (repo != null) put("repo", repo)
    }
}
