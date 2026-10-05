package bosca.git.transport

import bosca.git.model.LfsObject
import bosca.git.model.LfsUploadValidationException
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.LfsObjectService
import bosca.git.service.RepositoryService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.test.*

class GitLfsObjectRoutesTest {
    private val repositories = mockk<RepositoryService>()
    private val permissions = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val lfs = mockk<LfsObjectService>(relaxed = true)
    private val repository = Repository(id = UUID.random(), slug = "repo", name = "Repo", ownerId = UUID.random(), visibility = Visibility.PRIVATE)
    private val content = "LFS test content".toByteArray()
    private val oid = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val params get() = mapOf("owner" to "owner", "repo" to "repo", "oid" to oid)
    private val upload = GitLfsUploadRoute(repositories, permissions, lfs)
    private val download = GitLfsDownloadRoute(repositories, permissions, lfs)
    private val verify = GitLfsVerifyRoute(repositories, permissions, lfs)

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        coEvery { repositories.findByOwnerAndSlug(any(), any()) } returns repository
        coEvery { lfs.findByOid(any(), any()) } returns null
        coEvery { lfs.upload(any(), any(), any<Long>(), any()) } coAnswers {
            arg<suspend (java.io.OutputStream) -> Long>(3)(java.io.OutputStream.nullOutputStream())
            LfsObject(repositoryId = firstArg(), oid = secondArg(), size = thirdArg())
        }
    }

    @AfterTest
    fun teardown() = clearRouteProviders()

    private fun verification(oid: String = this.oid, size: Long = content.size.toLong()) =
        """{"oid":"$oid","size":$size}""".toByteArray()

    @Test
    fun `valid upload streams identical bytes into the LFS service`() = runTest {
        var stored = ByteArray(0)
        coEvery { lfs.upload(repository.id, oid, content.size.toLong(), any()) } coAnswers {
            val output = java.io.ByteArrayOutputStream()
            arg<suspend (java.io.OutputStream) -> Long>(3)(output)
            stored = output.toByteArray()
            LfsObject(repositoryId = repository.id, oid = oid, size = content.size.toLong())
        }
        val (call, response) = recordedCall(pathParameters = params, headers = mapOf("Content-Length" to content.size.toString()), body = content)
        runRoute(upload, call)
        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(content, stored)
    }

    @Test
    fun `service validation failure returns an LFS protocol error`() = runTest {
        coEvery { lfs.upload(any(), any(), any<Long>(), any()) } throws LfsUploadValidationException()
        val (call, response) = recordedCall(pathParameters = params, headers = mapOf("Content-Length" to content.size.toString()), body = content)
        runRoute(upload, call)
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `empty content with its correct digest is accepted`() = runTest {
        val emptyOid = MessageDigest.getInstance("SHA-256").digest(ByteArray(0)).joinToString("") { "%02x".format(it) }
        val (call, response) = recordedCall(pathParameters = params + ("oid" to emptyOid), headers = mapOf("Content-Length" to "0"))
        runRoute(upload, call)
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { lfs.upload(repository.id, emptyOid, 0, any()) }
    }

    @Test
    fun `missing or invalid content length is rejected`() = runTest {
        for (length in listOf(null, "bad", "-1")) {
            val headers = length?.let { mapOf("Content-Length" to it) }.orEmpty()
            val (call, response) = recordedCall(pathParameters = params, headers = headers, body = content)
            runRoute(upload, call)
            assertEquals(if (length == "-1") HttpStatusCode.BadRequest else HttpStatusCode.LengthRequired, response.status)
        }
    }

    @Test
    fun `invalid or missing oid is rejected by upload and download`() = runTest {
        for (route in listOf(upload, download)) {
            for (parameters in listOf(params - "oid", params + ("oid" to "../escape"))) {
                val (call, response) = recordedCall(pathParameters = parameters)
                runRoute(route, call)
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }
    }

    @Test
    fun `download streams existing content and reports its length`() = runTest {
        coEvery { lfs.findByOid(repository.id, oid) } returns LfsObject(repositoryId = repository.id, oid = oid, size = content.size.toLong())
        val input = content.inputStream()
        coEvery { lfs.download(repository.id, oid) } returns input
        val (call, response) = recordedCall(pathParameters = params)
        runRoute(download, call)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(content.size.toString(), response.headers["Content-Length"])
        assertContentEquals(content, response.streamed.toByteArray())
    }

    @Test
    fun `missing download and verification objects return 404`() = runTest {
        for (route in listOf(download, verify)) {
            val (call, response) = recordedCall(pathParameters = params, body = verification())
            runRoute(route, call)
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `verification checks the stored size`() = runTest {
        coEvery { lfs.findByOid(repository.id, oid) } returns LfsObject(repositoryId = repository.id, oid = oid, size = content.size.toLong())
        for (size in listOf(content.size.toLong(), content.size + 1L)) {
            val (call, response) = recordedCall(pathParameters = params, body = verification(size = size))
            runRoute(verify, call)
            assertEquals(if (size == content.size.toLong()) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity, response.status)
        }
    }

    @Test
    fun `invalid verification requests return 400`() = runTest {
        for (body in listOf("not-json".toByteArray(), verification(oid = "invalid"), verification(size = -1))) {
            val (call, response) = recordedCall(pathParameters = params, body = body)
            runRoute(verify, call)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `every object endpoint rejects unknown repositories`() = runTest {
        coEvery { repositories.findByOwnerAndSlug(any(), any()) } returns null
        for (route in listOf(upload, download, verify)) {
            val (call, response) = recordedCall(pathParameters = params, body = verification())
            runRoute(route, call)
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `denied anonymous callers receive a Basic challenge and authenticated callers receive 403`() = runTest {
        coEvery { permissions.verifyAllowed(any(), repository, any()) } throws SecurityException("denied")
        val principal = AuthenticatedPrincipal(Principal(id = UUID.random()), emptyList())
        for (identity in listOf(null, principal)) {
            val (call, response) = recordedCall(pathParameters = params, principal = identity)
            runRoute(download, call)
            assertEquals(if (identity == null) HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden, response.status)
            if (identity == null) assertEquals("Basic realm=\"Bosca Git\"", response.headers["WWW-Authenticate"])
        }
    }

    @Test
    fun `read and write token scopes are enforced independently`() = runTest {
        val principal = mockk<ScopedAuthenticatedPrincipal>(relaxed = true)
        for (route in listOf(download, upload, verify)) {
            val (call, response) = recordedCall(pathParameters = params, principal = principal, body = verification())
            runRoute(route, call)
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        every { principal.hasScope("git:read") } returns true
        val (call, response) = recordedCall(pathParameters = params, principal = principal)
        runRoute(download, call)
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `cancellation during upload and verify propagates`() = runTest {
        for (route in listOf(upload, verify)) {
            val (call, response) = recordedCall(pathParameters = params, headers = mapOf("Content-Length" to content.size.toString()), body = content)
            val request = call.request
            coEvery { request.bodyStreamTo(any(), any()) } throws CancellationException("cancelled")
            coEvery { request.bodyText() } throws CancellationException("cancelled")
            val failure = runCatching { runRoute(route, call) }.exceptionOrNull()
            assertTrue(failure is CancellationException, "${route.javaClass.simpleName}: $failure, status=${response.status}, body=${response.bodyText}")
        }
    }
}
