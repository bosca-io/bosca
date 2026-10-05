package bosca.git.transport

import bosca.git.model.LfsObject
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.LfsObjectService
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GitLfsBatchRouteTest {
    private val repositories = mockk<RepositoryService>()
    private val permissions = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val lfs = mockk<LfsObjectService>()
    private val repository = Repository(id = UUID.random(), slug = "repo", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE)
    private val oid = "a".repeat(64)
    private val params = mapOf("owner" to "owner", "repo" to "repo")
    private val app = BoscaApplication(ApplicationConfig.load("git:\n  url: https://git.example.com/\n".byteInputStream()))
    private val route = GitLfsBatchRoute(repositories, permissions, lfs, app)

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        coEvery { repositories.findByOwnerAndSlug("owner", "repo") } returns repository
        coEvery { lfs.findByOid(any(), any()) } returns null
    }

    @AfterTest
    fun teardown() = clearRouteProviders()

    private fun batch(operation: String, oid: String = this.oid, size: Long = 10) =
        """{"operation":"$operation","objects":[{"oid":"$oid","size":$size}]}""".toByteArray()

    private fun firstObject(response: RecordedResponse) =
        Json.parseToJsonElement(response.bodyText).jsonObject.getValue("objects").jsonArray.first().jsonObject

    @Test
    fun `missing repository path parameters return 400`() = runTest {
        for (params in listOf(mapOf("owner" to "owner"), mapOf("repo" to "repo"))) {
            val (call, response) = recordedCall(pathParameters = params, body = batch("download"))
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `unknown repository returns 404`() = runTest {
        coEvery { repositories.findByOwnerAndSlug(any(), any()) } returns null
        val (call, response) = recordedCall(pathParameters = params, body = batch("download"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `invalid JSON and operation return a JSON error`() = runTest {
        for (body in listOf("nope".toByteArray(), batch("delete"))) {
            val (call, response) = recordedCall(pathParameters = params, body = body)
            runRoute(route, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(Json.parseToJsonElement(response.bodyText).jsonObject.containsKey("message"))
        }
    }

    @Test
    fun `download returns an absolute URL and forwards request authentication`() = runTest {
        coEvery { lfs.findByOid(repository.id, oid) } returns LfsObject(repositoryId = repository.id, oid = oid, size = 10)
        val (call, response) = recordedCall(pathParameters = params, body = batch("download"), headers = mapOf("Authorization" to "Basic test"))
        runRoute(route, call)
        coVerify { permissions.verifyAllowed(any(), repository, PermissionAction.VIEW) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(lfsContentType, response.contentType)
        val action = firstObject(response).getValue("actions").jsonObject.getValue("download").jsonObject
        assertEquals("https://git.example.com/owner/repo.git/info/lfs/objects/$oid", action.getValue("href").jsonPrimitive.content)
        assertEquals("Basic test", action.getValue("header").jsonObject.getValue("Authorization").jsonPrimitive.content)
    }

    @Test
    fun `upload includes upload and verify actions`() = runTest {
        val (call, response) = recordedCall(pathParameters = params, body = batch("upload"))
        runRoute(route, call)
        coVerify { permissions.verifyAllowed(any(), repository, PermissionAction.EDIT) }
        assertEquals(HttpStatusCode.OK, response.status)
        val actions = firstObject(response).getValue("actions").jsonObject
        assertEquals(setOf("upload", "verify"), actions.keys)
        assertEquals("https://git.example.com/owner/repo.git/info/lfs/objects/verify", actions.getValue("verify").jsonObject.getValue("href").jsonPrimitive.content)
    }

    @Test
    fun `current client fields and protocol extensions are accepted`() = runTest {
        val body = """{"operation":"upload","transfers":["basic"],"ref":{"name":"refs/heads/main"},"hash_algo":"sha256","extension":true,"objects":[{"oid":"$oid","size":10,"extension":true}]}""".toByteArray()
        val (call, response) = recordedCall(pathParameters = params, body = body)
        runRoute(route, call)
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyText).jsonObject
        assertEquals("basic", json.getValue("transfer").jsonPrimitive.content)
        assertEquals("sha256", json.getValue("hash_algo").jsonPrimitive.content)
    }

    @Test
    fun `unsupported algorithm or transfer adapter returns 422`() = runTest {
        for (extra in listOf("\"hash_algo\":\"sha512\"", "\"transfers\":[\"unsupported\"]")) {
            val (call, response) = recordedCall(pathParameters = params, body = """{"operation":"upload","objects":[],$extra}""".toByteArray())
            runRoute(route, call)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }
    }

    @Test
    fun `invalid objects have per-object errors and never reach storage`() = runTest {
        for (body in listOf(batch("upload", "../escape"), batch("upload", size = -1))) {
            val (call, response) = recordedCall(pathParameters = params, body = body)
            runRoute(route, call)
            assertEquals(422, firstObject(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        }
        coVerify(exactly = 0) { lfs.findByOid(any(), any()) }
    }

    @Test
    fun `missing download returns per-object 404`() = runTest {
        val (call, response) = recordedCall(pathParameters = params, body = batch("download"))
        runRoute(route, call)
        assertEquals(404, firstObject(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun `existing upload has no actions and inconsistent size is rejected`() = runTest {
        coEvery { lfs.findByOid(repository.id, oid) } returns LfsObject(repositoryId = repository.id, oid = oid, size = 10)
        val (call, response) = recordedCall(pathParameters = params, body = batch("upload"))
        runRoute(route, call)
        assertFalse(firstObject(response).containsKey("actions"))
        val (badCall, badResponse) = recordedCall(pathParameters = params, body = batch("upload", size = 11))
        runRoute(route, badCall)
        assertEquals(422, firstObject(badResponse).getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
    }

    @Test
    fun `missing or blank Git URL returns 503`() = runTest {
        for (yaml in listOf("{}", "git:\n  url: ' '\n")) {
            val route = GitLfsBatchRoute(repositories, permissions, lfs, BoscaApplication(ApplicationConfig.load(yaml.byteInputStream())))
            val (call, response) = recordedCall(pathParameters = params, body = batch("upload"))
            runRoute(route, call)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }

    @Test
    fun `cancellation while reading the request propagates`() = runTest {
        val (call, _) = recordedCall(pathParameters = params)
        coEvery { call.request.bodyText() } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { runRoute(route, call) }
    }
}
