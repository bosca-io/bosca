package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
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
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Drives [GitRawFileRoute]: parameter guards, missing repo/ref/path 404s, and
 * a streamed file body with content-type inference across every extension arm.
 */
class GitRawFileRouteTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val route = GitRawFileRoute(repositoryService, dfsManager, permissionEvaluator)

    private val repositoryId = UUID.random()
    private val repository = Repository(id = repositoryId, slug = "repo", name = "R", ownerId = UUID.random(), visibility = Visibility.PUBLIC)
    private lateinit var gitRepo: InMemoryRepository

    // One file per content-type arm, all committed under refs/heads/main.
    private val files = listOf(
        "index.html", "style.css", "app.js", "data.json", "conf.xml", "readme.md",
        "pic.png", "photo.jpg", "anim.gif", "icon.svg", "doc.pdf", "blob.bin", "LICENSE",
    )

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        gitRepo = InMemoryRepository(DfsRepositoryDescription("raw"))
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
        val tree = TreeFormatter()
        for (name in files.sorted()) {
            tree.append(name, FileMode.REGULAR_FILE, ins.insert(Constants.OBJ_BLOB, "content of $name".toByteArray()))
        }
        val treeId = ins.insert(tree)
        val author = PersonIdent("T", "t@x")
        val commit = ins.insert(CommitBuilder().apply { setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("c") })
        ins.flush()
        gitRepo.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(commit); update() }
    }

    private fun params(ref: String = "refs/heads/main", path: String = "readme.md") =
        mapOf("owner" to "owner", "repo" to "repo", "ref" to ref, "path" to path)

    @Test
    fun `missing path parameters return 400`() = runTest {
        val (call, rec) = recordedCall(pathParameters = mapOf("owner" to "owner", "repo" to "repo"))
        runRoute(route, call)
        assertEquals(HttpStatusCode.BadRequest, rec.status)
    }

    @Test
    fun `unknown repository, ref, or file return 404`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("owner", "gone") } returns null
        recordedCall(pathParameters = params() + ("repo" to "gone")).let { (c, r) ->
            runRoute(route, c); assertEquals(HttpStatusCode.NotFound, r.status)
        }
        recordedCall(pathParameters = params(ref = "refs/heads/nope")).let { (c, r) ->
            runRoute(route, c); assertEquals(HttpStatusCode.NotFound, r.status)
        }
        recordedCall(pathParameters = params(path = "missing.txt")).let { (c, r) ->
            runRoute(route, c); assertEquals(HttpStatusCode.NotFound, r.status)
        }
    }

    @Test
    fun `streams the file body with a content length`() = runTest {
        val (call, rec) = recordedCall(pathParameters = params(path = "readme.md"))
        runRoute(route, call)

        assertEquals("content of readme.md", rec.streamed.toByteArray().toString(Charsets.UTF_8))
        assertEquals("content of readme.md".length.toString(), rec.headers["Content-Length"])
        assertEquals("text", rec.contentType?.contentType)
    }

    @Test
    fun `repository reads leave the request thread`() = runTest {
        val requestThread = Thread.currentThread()
        var openThread: Thread? = null
        every { dfsManager.open(repositoryId) } answers {
            openThread = Thread.currentThread()
            gitRepo
        }

        val (call, rec) = recordedCall(pathParameters = params())
        runRoute(route, call)

        assertNotSame(requestThread, assertNotNull(openThread))
        assertEquals("content of readme.md", rec.streamed.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `content type is inferred from every known extension`() = runTest {
        val expected = mapOf(
            "index.html" to "text/html", "style.css" to "text/css", "app.js" to "text/javascript",
            "data.json" to "application/json", "conf.xml" to "application/xml", "readme.md" to "text/plain",
            "pic.png" to "image/png", "photo.jpg" to "image/jpeg", "anim.gif" to "image/gif",
            "icon.svg" to "image/svg+xml", "doc.pdf" to "application/pdf",
            "blob.bin" to "application/octet-stream", "LICENSE" to "application/octet-stream",
        )
        for ((file, mime) in expected) {
            val (call, rec) = recordedCall(pathParameters = params(path = file))
            runRoute(route, call)
            val actual = "${rec.contentType?.contentType}/${rec.contentType?.contentSubtype}"
            assertEquals(mime, actual, "wrong content type for $file")
            assertTrue(rec.streamed.size() > 0, "no body for $file")
        }
    }
}
