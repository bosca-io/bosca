package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [GitUploadPackRoute] through the public route entry: parameter and
 * permission guards plus a real protocol-v2 `ls-refs` exchange (plain and
 * gzip-encoded) against an in-memory DFS repository.
 */
class GitUploadPackRouteTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)

    private val route = GitUploadPackRoute(repositoryService, dfsManager, permissionEvaluator)

    private val repositoryId = UUID.random()
    private val repository = Repository(id = repositoryId, slug = "repo", name = "R", ownerId = UUID.random(), visibility = Visibility.PUBLIC)
    private lateinit var gitRepo: InMemoryRepository

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        gitRepo = InMemoryRepository(DfsRepositoryDescription("upload-pack"))
        seedCommit()
        every { dfsManager.open(repositoryId) } returns gitRepo
        coEvery { repositoryService.findByOwnerAndSlug("owner", "repo") } returns repository
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        clearRouteProviders()
    }

    private fun seedCommit() {
        val ins = gitRepo.objectDatabase.newInserter()
        val blob = ins.insert(Constants.OBJ_BLOB, "hi\n".toByteArray())
        val treeId = ins.insert(TreeFormatter().apply { append("f.txt", FileMode.REGULAR_FILE, blob) })
        val author = PersonIdent("T", "t@x")
        val commit = ins.insert(CommitBuilder().apply { setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("c") })
        ins.flush()
        gitRepo.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(commit); update() }
    }

    private fun lsRefsBody(): ByteArray =
        GitInfoRefsRoute.pktLine("command=ls-refs\n") + GitInfoRefsRoute.pktFlush()

    private fun fetchBody(objectId: String): ByteArray =
        GitInfoRefsRoute.pktLine("command=fetch\n") +
            "0001".toByteArray() +
            GitInfoRefsRoute.pktLine("want $objectId\n") +
            GitInfoRefsRoute.pktLine("done\n") +
            GitInfoRefsRoute.pktFlush()

    private val params = mapOf("owner" to "owner", "repo" to "repo")

    @Test
    fun `missing path parameters return 400`() = runTest {
        val (call, rec) = recordedCall(pathParameters = mapOf("owner" to "owner"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)
    }

    @Test
    fun `unknown repository returns 404`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("owner", "repo") } returns null
        val (call, rec) = recordedCall(pathParameters = params)
        runRoute(route, call)
        assertEquals(HttpStatusCode.NotFound, rec.status)
        assertTrue(rec.bodyText.contains("ERR Repository not found"))
    }

    @Test
    fun `scoped token without read scope is rejected`() = runTest {
        val scoped = mockk<ScopedAuthenticatedPrincipal>(relaxed = true)
        every { scoped.hasScope("git:read") } returns false
        val (call, rec) = recordedCall(pathParameters = params, principal = scoped)
        runRoute(route, call)
        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("git:read"))
    }

    @Test
    fun `anonymous denial returns 401 with challenge, authenticated returns 403`() = runTest {
        coEvery { permissionEvaluator.verifyAllowed(any(), repository, any()) } throws SecurityException("no")

        recordedCall(pathParameters = params).let { (call, rec) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.Unauthorized, rec.status)
            assertEquals("Basic realm=\"Bosca Git\"", rec.headers["WWW-Authenticate"])
        }

        val principal = mockk<bosca.security.model.AuthenticatedPrincipal>(relaxed = true)
        recordedCall(pathParameters = params, principal = principal).let { (call, rec) ->
            runRoute(route, call)
            assertEquals(HttpStatusCode.Forbidden, rec.status)
        }
    }

    @Test
    fun `ls-refs request streams the advertised refs`() = runTest {
        val (call, rec) = recordedCall(pathParameters = params, body = lsRefsBody())
        runRoute(route, call)

        val text = rec.streamed.toByteArray().toString(Charsets.UTF_8)
        assertTrue(text.contains("refs/heads/main"), "refs missing from: $text")
    }

    @Test
    fun `gzip-encoded request body is transparently decompressed`() = runTest {
        val gz = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(lsRefsBody()) }
        }.toByteArray()
        val (call, rec) = recordedCall(
            pathParameters = params,
            headers = mapOf("Content-Encoding" to "gzip"),
            body = gz,
        )
        runRoute(route, call)

        val text = rec.streamed.toByteArray().toString(Charsets.UTF_8)
        assertTrue(text.contains("refs/heads/main"), "refs missing from: $text")
    }

    @Test
    fun `missing wanted object returns a complete git protocol error`() = runTest {
        val missingObjectId = "0123456789012345678901234567890123456789"
        val (call, rec) = recordedCall(
            pathParameters = params,
            body = fetchBody(missingObjectId),
        )

        runRoute(route, call)

        val text = rec.streamed.toByteArray().toString(Charsets.UTF_8)
        assertTrue(
            text.contains("ERR want $missingObjectId not valid"),
            "protocol error missing from: $text",
        )
    }
}
