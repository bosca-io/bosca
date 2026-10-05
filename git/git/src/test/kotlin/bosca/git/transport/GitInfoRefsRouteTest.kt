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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [GitInfoRefsRoute] through the public route entry with a recorded
 * [bosca.server.ServerCall]: parameter guards, repo lookup, permission/scope
 * denials, and both smart-HTTP advertisement streams against a real in-memory
 * DFS repository.
 */
class GitInfoRefsRouteTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)

    private val route = GitInfoRefsRoute(repositoryService, dfsManager, permissionEvaluator)

    private val repositoryId = UUID.random()
    private val repository = Repository(id = repositoryId, slug = "repo", name = "R", ownerId = UUID.random(), visibility = Visibility.PUBLIC)
    private lateinit var gitRepo: InMemoryRepository

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        gitRepo = InMemoryRepository(DfsRepositoryDescription("info-refs"))
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

    private fun params(owner: String? = "owner", repo: String? = "repo") = buildMap {
        if (owner != null) put("owner", owner)
        if (repo != null) put("repo", repo)
    }

    @Test
    fun `missing path or service parameters return 400`() = runTest {
        var (call, rec) = recordedCall(pathParameters = params(owner = null), queryParameters = mapOf("service" to "git-upload-pack"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)

        recordedCall(pathParameters = params(repo = null), queryParameters = mapOf("service" to "git-upload-pack")).let { (c, r) ->
            runRoute(route, c); assertEquals(HttpStatusCode.BadRequest, r.status)
        }

        recordedCall(pathParameters = params()).let { (c, r) ->
            runRoute(route, c); assertEquals(HttpStatusCode.BadRequest, r.status)
        }
    }

    @Test
    fun `unknown repository returns 404 with a git error packet`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("owner", "gone") } returns null
        val (call, rec) = recordedCall(pathParameters = params(repo = "gone"), queryParameters = mapOf("service" to "git-upload-pack"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.NotFound, rec.status)
        assertTrue(rec.bodyText.contains("ERR Repository not found"))
    }

    @Test
    fun `unknown service returns 400`() = runTest {
        val (call, rec) = recordedCall(pathParameters = params(), queryParameters = mapOf("service" to "git-frobnicate"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)
    }

    @Test
    fun `upload-pack advertisement streams the service banner and refs`() = runTest {
        val (call, rec) = recordedCall(pathParameters = params(), queryParameters = mapOf("service" to "git-upload-pack"))
        runRoute(route, call)

        assertEquals("no-cache", rec.headers["Cache-Control"])
        val text = rec.bodyText
        assertTrue(text.contains("# service=git-upload-pack"), "banner missing: $text")
        // JGit negotiates protocol v2 here: info/refs advertises capabilities
        // (ls-refs/fetch), and the actual refs are served by a later ls-refs call.
        assertTrue(text.contains("version 2") && text.contains("ls-refs"), "v2 capabilities missing: $text")
    }

    @Test
    fun `receive-pack advertisement streams the service banner and refs`() = runTest {
        val (call, rec) = recordedCall(pathParameters = params(), queryParameters = mapOf("service" to "git-receive-pack"))
        runRoute(route, call)

        val text = rec.bodyText
        assertTrue(text.contains("# service=git-receive-pack"), "banner missing: $text")
        assertTrue(text.contains("refs/heads/main"), "refs missing: $text")
    }

    @Test
    fun `anonymous permission denial returns 401 with an auth challenge`() = runTest {
        coEvery { permissionEvaluator.verifyAllowed(any(), repository, any()) } throws SecurityException("denied")
        val (call, rec) = recordedCall(pathParameters = params(), queryParameters = mapOf("service" to "git-upload-pack"))
        runRoute(route, call)

        assertEquals(HttpStatusCode.Unauthorized, rec.status)
        assertEquals("Basic realm=\"Bosca Git\"", rec.headers["WWW-Authenticate"])
        assertTrue(rec.bodyText.contains("ERR Authentication required"))
    }

    @Test
    fun `authenticated permission denial returns 403`() = runTest {
        coEvery { permissionEvaluator.verifyAllowed(any(), repository, any()) } throws SecurityException("denied")
        val principal = mockk<bosca.security.model.AuthenticatedPrincipal>(relaxed = true)
        val (call, rec) = recordedCall(
            pathParameters = params(), queryParameters = mapOf("service" to "git-upload-pack"),
            principal = principal,
        )
        runRoute(route, call)

        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("ERR Permission denied"))
    }

    @Test
    fun `scoped token without the read scope is rejected with 403`() = runTest {
        val scoped = mockk<ScopedAuthenticatedPrincipal>(relaxed = true)
        every { scoped.hasScope("git:read") } returns false
        val (call, rec) = recordedCall(
            pathParameters = params(), queryParameters = mapOf("service" to "git-upload-pack"),
            principal = scoped,
        )
        runRoute(route, call)

        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("ERR Token missing required scope: git:read"))
    }

    @Test
    fun `scoped token without the write scope cannot advertise receive-pack`() = runTest {
        val scoped = mockk<ScopedAuthenticatedPrincipal>(relaxed = true)
        every { scoped.hasScope("git:write") } returns false
        val (call, rec) = recordedCall(
            pathParameters = params(), queryParameters = mapOf("service" to "git-receive-pack"),
            principal = scoped,
        )
        runRoute(route, call)

        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("git:write"))
    }

    @Test
    fun `pkt-line helpers frame data with a length prefix`() {
        assertEquals("001e# service=git-upload-pack\n", String(GitInfoRefsRoute.pktLine("# service=git-upload-pack\n")))
        assertEquals("0000", String(GitInfoRefsRoute.pktFlush()))
        assertTrue(String(GitInfoRefsRoute.gitErrorPacket("boom")).endsWith("ERR boom\n"))
    }
}
