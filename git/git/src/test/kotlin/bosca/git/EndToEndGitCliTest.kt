@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.BranchProtectionRule
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.BranchProtectionService
import bosca.git.service.PushRateLimiterImpl
import bosca.git.service.RefUpdateNotifier
import bosca.git.service.RepositoryInitializerImpl
import bosca.git.service.RepositoryService
import bosca.git.transport.GitInfoRefsRoute
import bosca.git.transport.GitLfsBatchRoute
import bosca.git.transport.GitLfsUploadRoute
import bosca.git.transport.GitLfsDownloadRoute
import bosca.git.transport.GitLfsVerifyRoute
import bosca.git.model.LfsObject
import bosca.git.repository.LfsObjectRepository
import bosca.git.service.LfsObjectService
import bosca.git.service.LfsObjectServiceImpl
import bosca.storage.service.FileSystemObjectStorageService
import bosca.git.transport.GitPostReceiveHook
import bosca.git.transport.GitPreReceiveHook
import bosca.git.transport.GitRawFileRoute
import bosca.git.transport.GitReceivePackRoute
import bosca.git.transport.GitRepositoryRedirectRoute
import bosca.git.transport.GitUploadPackRoute
import bosca.git.transport.TeamCityWebhookRoute
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.routes.configureGitRoutes
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.routes.BoscaAuthMiddleware
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.pack.PackExt
import org.eclipse.jgit.lib.NullProgressMonitor
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * True end-to-end tests: a real `git` command-line client speaks the smart HTTP
 * protocol over a real TCP socket to a real [NettyServerEngine] running the
 * PRODUCTION route table (the KSP-generated [configureGitRoutes] wiring, with
 * its authentication scoping intact) and the production [BoscaAuthMiddleware]
 * Basic-auth parsing, backed by the Bosca DFS repository layer.
 *
 * This is the only suite where the actual `git` binary exercises the stack —
 * covering what in-process JGit tests structurally cannot: chunked request
 * bodies, pkt-line framing across real sockets, protocol v2 negotiation with a
 * stock client, and the 401 challenge → Basic credential retry dance.
 *
 * Only domain lookups that have their own dedicated suites are stubbed:
 * repository-by-slug resolution, the permission policy decision, credential
 * validation, and branch-protection rules. The transport routes, JGit
 * UploadPack/ReceivePack, pre/post-receive hooks, push rate limiter, write
 * lock, and DFS adapters all run for real.
 *
 * One server is booted for the whole suite and is intentionally never stopped:
 * [NettyServerEngine.stop] ends with `Runtime.getRuntime().halt(0)` (a
 * production shutdown guarantee), which would kill the test JVM mid-suite.
 * The engine runs on daemon threads and dies with the test worker instead.
 * Test isolation comes from per-test repositories: every test mints its own
 * slug, and the DFS adapters namespace all state by repository id.
 */
class EndToEndGitCliTest {

    /** Per-test scratch directory; all clones and work trees live under it. */
    private lateinit var space: Path

    @BeforeTest
    fun createSpace() {
        space = Files.createTempDirectory(workRoot, "test")
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `opening repository URLs with or without git suffix redirects to the repository in Studio`() {
        val repository = newRepository(Visibility.PRIVATE)
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        listOf(anonymousUrl(repository), anonymousUrl(repository).removeSuffix(".git")).forEach { url ->
            val response = client.send(
                HttpRequest.newBuilder(URI(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )

            assertEquals(302, response.statusCode(), url)
            assertEquals(
                "https://studio.example.com/git/repositories/${repository.id}",
                response.headers().firstValue("Location").orElse(null),
                url,
            )
        }
    }

    @Test
    fun `push to an empty repository and anonymous clone returns identical content`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = newWorkDir("origin")
        val binaryContent = Random(42).nextBytes(1024 * 1024)
        gitInit(origin)
        origin.resolve("README.md").toFile().writeText("# App\n\nHello, Bosca!\n")
        origin.resolve("src").toFile().mkdirs()
        origin.resolve("src/main.kt").toFile().writeText("fun main() = println(\"hello\")\n")
        origin.resolve("asset.bin").toFile().writeBytes(binaryContent)
        commitAll(origin, "Initial commit")
        gitOk(origin, "push", authUrl(repository), "main")

        val clone = space.resolve("clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())

        assertEquals(
            origin.resolve("README.md").toFile().readText(),
            clone.resolve("README.md").toFile().readText(),
        )
        assertEquals(
            origin.resolve("src/main.kt").toFile().readText(),
            clone.resolve("src/main.kt").toFile().readText(),
        )
        assertEquals(
            sha256(origin.resolve("asset.bin").toFile()),
            sha256(clone.resolve("asset.bin").toFile()),
            "Binary file must round-trip byte-for-byte",
        )
        assertEquals(revParseHead(origin), revParseHead(clone), "Clone must be at the pushed commit")
        assertEquals("Initial commit", gitOk(clone, "log", "-1", "--format=%s").stdout.trim())

        assertTrue(
            storageAdapter.countCommittedPacks(repository.id) > 0,
            "Push must land committed packs in DFS storage",
        )
        coVerify(atLeast = 1) {
            refUpdateNotifier.notifyRefsUpdated(
                repository = any(),
                repositoryId = repository.id,
                updates = match { updates ->
                    updates.any { it.refName == "refs/heads/main" }
                },
                pusherPrincipalId = principal.id,
            )
        }
    }

    @Test
    fun `clone and fetch from unrelated history succeed after garbage collection`() {
        cloneAndFetchAfterGarbageCollection(clearPackMetadata = false)
    }

    @Test
    fun `clone and fetch recover garbage collected packs with zero object count metadata`() {
        cloneAndFetchAfterGarbageCollection(clearPackMetadata = true)
    }

    private fun cloneAndFetchAfterGarbageCollection(clearPackMetadata: Boolean) {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = newWorkDir("gc-origin")
        gitInit(origin)
        origin.resolve("README.md").toFile().writeText("# App\n")
        commitAll(origin, "Initial commit")
        gitOk(origin, "push", authUrl(repository), "main")

        dfsManager.open(repository.id).use { repo ->
            assertTrue(DfsGarbageCollector(repo).pack(NullProgressMonitor.INSTANCE))
        }
        val packs = storageAdapter.listPacks(repository.id)
        assertTrue(packs.any { PackExt.BITMAP_INDEX.extension in it.extensions })
        assertEquals(3L, packs.sumOf { it.objectCount })
        packs.forEach { assertEquals(it.extensions.getValue(PackExt.PACK.extension).fileSize, it.fileSize) }
        if (clearPackMetadata) {
            packs.forEach { info ->
                storageAdapter.packs.getValue(info.id).apply {
                    objectCount = 0
                    fileSize = 0
                }
            }
        }

        val client = newWorkDir("unrelated-history")
        gitInit(client)
        client.resolve("local.txt").toFile().writeText("Independent local history\n")
        commitAll(client, "Local commit")
        val localHead = revParseHead(client)
        gitOk(client, "fetch", anonymousUrl(repository), "main")
        assertEquals(revParseHead(origin), gitOk(client, "rev-parse", "FETCH_HEAD").stdout.trim())
        assertEquals(localHead, revParseHead(client))
        gitOk(client, "fsck", "--full")

        val clone = space.resolve("gc-clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())
        assertEquals(revParseHead(origin), revParseHead(clone))
        assertEquals("# App\n", clone.resolve("README.md").toFile().readText())
        gitOk(clone, "fsck", "--full")
    }

    @Test
    fun `git lfs uploads and downloads private repository objects through the generated routes`() {
        val repository = newRepository(Visibility.PRIVATE)
        val origin = newWorkDir("lfs-origin")
        gitInit(origin)
        gitOk(origin, "lfs", "install", "--local")
        gitOk(origin, "lfs", "track", "*.bin")
        val content = Random(57).nextBytes(1024 * 1024 + 37)
        origin.resolve("asset.bin").toFile().writeBytes(content)
        commitAll(origin, "Add LFS asset")
        gitOk(origin, "remote", "add", "origin", authUrl(repository))
        gitOk(origin, "push", "origin", "main")
        gitOk(origin, "lfs", "push", "--all", "origin")

        val clone = space.resolve("lfs-clone")
        gitOk(space, "clone", authUrl(repository), clone.toString())
        gitOk(clone, "lfs", "install", "--local")
        gitOk(clone, "lfs", "pull")

        assertEquals(sha256(origin.resolve("asset.bin").toFile()), sha256(clone.resolve("asset.bin").toFile()))
        val objects = lfsObjects.values.filter { it.repositoryId == repository.id }
        assertEquals(1, objects.size)
        assertEquals(content.size.toLong(), objects.single().size)
        assertEquals(sha256(origin.resolve("asset.bin").toFile()), objects.single().oid)
    }

    @Test
    fun `public git lfs objects can be downloaded without credentials`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = newWorkDir("public-lfs-origin")
        gitInit(origin)
        gitOk(origin, "lfs", "install", "--local")
        gitOk(origin, "lfs", "track", "*.bin")
        origin.resolve("public.bin").toFile().writeBytes(Random(19).nextBytes(8192))
        commitAll(origin, "Add public LFS asset")
        gitOk(origin, "remote", "add", "origin", authUrl(repository))
        gitOk(origin, "push", "origin", "main")

        val clone = space.resolve("public-lfs-clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())
        gitOk(clone, "lfs", "install", "--local")
        gitOk(clone, "lfs", "pull")

        assertEquals(sha256(origin.resolve("public.bin").toFile()), sha256(clone.resolve("public.bin").toFile()))
    }

    @Test
    fun `pushed branches and annotated tags are advertised and cloned`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)

        gitOk(origin, "checkout", "-b", "feature")
        origin.resolve("feature.txt").toFile().writeText("feature work\n")
        commitAll(origin, "Add feature work")
        gitOk(origin, "checkout", "main")
        gitOk(origin, "tag", "-a", "v1.0.0", "-m", "Release 1.0.0")
        gitOk(origin, "push", authUrl(repository), "main", "feature", "v1.0.0")

        val refs = gitOk(space, "ls-remote", anonymousUrl(repository)).stdout
        assertTrue("refs/heads/main" in refs, "main missing from advertisement:\n$refs")
        assertTrue("refs/heads/feature" in refs, "feature missing from advertisement:\n$refs")
        assertTrue("refs/tags/v1.0.0" in refs, "tag missing from advertisement:\n$refs")
        assertTrue("refs/tags/v1.0.0^{}" in refs, "peeled tag missing (annotated tag must be a tag object):\n$refs")

        val clone = space.resolve("clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())
        assertEquals("v1.0.0", gitOk(clone, "tag", "--list").stdout.trim())
        assertTrue(
            "Release 1.0.0" in gitOk(clone, "cat-file", "tag", "v1.0.0").stdout,
            "Annotated tag message must survive the round trip",
        )
        assertTrue(
            "origin/feature" in gitOk(clone, "branch", "--remotes").stdout,
            "Remote branch must be visible in the clone",
        )
    }

    @Test
    fun `incremental fetch after new commits fast-forwards the clone`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        val clone = space.resolve("clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())

        origin.resolve("CHANGELOG.md").toFile().writeText("## 1.1\n- things\n")
        commitAll(origin, "Add changelog")
        origin.resolve("README.md").toFile().appendText("\nMore docs.\n")
        commitAll(origin, "Extend readme")
        gitOk(origin, "push", authUrl(repository), "main")

        gitOk(clone, "pull", "--ff-only")
        assertEquals(revParseHead(origin), revParseHead(clone), "Pull must fast-forward to the new head")
        assertEquals("## 1.1\n- things\n", clone.resolve("CHANGELOG.md").toFile().readText())
    }

    @Test
    fun `fetching an unavailable object returns a complete protocol error`() {
        val repository = newRepository(Visibility.PUBLIC)
        val client = newWorkDir("client")
        val missingObjectId = "0123456789012345678901234567890123456789"
        gitInit(client)

        val fetch = git(client, "fetch", anonymousUrl(repository), missingObjectId)

        assertNotEquals(0, fetch.exitCode, fetch.output)
        assertTrue("want $missingObjectId not valid" in fetch.output, fetch.output)
        assertFalse("RPC failed" in fetch.output, fetch.output)
        assertFalse("expected 'acknowledgments'" in fetch.output, fetch.output)
        assertFalse("transfer closed" in fetch.output, fetch.output)
    }

    @Test
    fun `deleting a remote branch removes it from the advertisement`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        gitOk(origin, "checkout", "-b", "short-lived")
        origin.resolve("tmp.txt").toFile().writeText("temp\n")
        commitAll(origin, "Temp work")
        gitOk(origin, "push", authUrl(repository), "short-lived")
        assertTrue("refs/heads/short-lived" in gitOk(space, "ls-remote", anonymousUrl(repository)).stdout)

        gitOk(origin, "push", authUrl(repository), ":refs/heads/short-lived")

        val refs = gitOk(space, "ls-remote", anonymousUrl(repository)).stdout
        assertFalse("refs/heads/short-lived" in refs, "Deleted branch must disappear:\n$refs")
        assertTrue("refs/heads/main" in refs, "main must survive the branch deletion:\n$refs")
    }

    @Test
    fun `force push rewrites history on the server`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        val before = revParseHead(origin)

        gitOk(origin, "commit", "--amend", "-m", "Rewritten initial commit")
        val amended = revParseHead(origin)
        assertNotEquals(before, amended)
        gitOk(origin, "push", "--force", authUrl(repository), "main")

        val clone = space.resolve("clone")
        gitOk(space, "clone", anonymousUrl(repository), clone.toString())
        assertEquals(amended, revParseHead(clone))
        assertEquals("Rewritten initial commit", gitOk(clone, "log", "-1", "--format=%s").stdout.trim())
    }

    @Test
    fun `shallow clone fetches only the newest commit`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        repeat(2) { index ->
            origin.resolve("file$index.txt").toFile().writeText("content $index\n")
            commitAll(origin, "Commit $index")
        }
        gitOk(origin, "push", authUrl(repository), "main")

        val clone = space.resolve("shallow")
        gitOk(space, "clone", "--depth", "1", anonymousUrl(repository), clone.toString())

        assertEquals("1", gitOk(clone, "rev-list", "--count", "HEAD").stdout.trim())
        assertEquals(revParseHead(origin), revParseHead(clone), "Shallow clone must be at the tip")
        assertEquals("content 1\n", clone.resolve("file1.txt").toFile().readText())
    }

    @Test
    fun `anonymous and wrongly-authenticated pushes are rejected without changing refs`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        val sealedHead = revParseHead(origin)

        origin.resolve("evil.txt").toFile().writeText("should not land\n")
        commitAll(origin, "Unauthorized change")

        val anonymousPush = git(origin, "push", anonymousUrl(repository), "main")
        assertNotEquals(0, anonymousPush.exitCode, "Anonymous push must fail:\n${anonymousPush.output}")

        val badCredentialsPush = git(origin, "push", url(repository, "$USERNAME:not-the-password"), "main")
        assertNotEquals(0, badCredentialsPush.exitCode, "Wrong password must fail:\n${badCredentialsPush.output}")

        val remoteHead = gitOk(space, "ls-remote", anonymousUrl(repository), "refs/heads/main").stdout
        assertTrue(sealedHead in remoteHead, "Rejected pushes must leave the remote head untouched:\n$remoteHead")

        gitOk(origin, "push", authUrl(repository), "main")
        val updatedHead = gitOk(space, "ls-remote", anonymousUrl(repository), "refs/heads/main").stdout
        assertTrue(revParseHead(origin) in updatedHead, "Authenticated push must advance the remote head")
    }

    @Test
    fun `protected main rejects normally while a new branch with the same objects pushes successfully`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        val remoteMain = revParseHead(origin)
        branchProtectionRules[repository.id] = listOf(
            BranchProtectionRule(
                repositoryId = repository.id,
                pattern = "main",
                requirePullRequest = true,
            )
        )

        origin.resolve("feature.txt").toFile().writeText("feature work\n")
        commitAll(origin, "Feature work")

        val protectedPush = git(origin, "push", authUrl(repository), "main")
        assertNotEquals(0, protectedPush.exitCode, protectedPush.output)
        assertTrue(
            "Branch 'main' is protected: changes must be made through a pull request" in protectedPush.output,
            protectedPush.output,
        )
        assertFalse("RPC failed" in protectedPush.output, protectedPush.output)
        assertFalse("remote end hung up" in protectedPush.output, protectedPush.output)

        gitOk(origin, "checkout", "-b", "kjb/test")
        val branchPush = git(origin, "push", authUrl(repository), "kjb/test")
        assertEquals(0, branchPush.exitCode, branchPush.output)
        assertFalse("RPC failed" in branchPush.output, branchPush.output)
        assertFalse("remote end hung up" in branchPush.output, branchPush.output)

        val refs = gitOk(space, "ls-remote", anonymousUrl(repository)).stdout
        assertTrue("refs/heads/kjb/test" in refs, refs)
        assertTrue("$remoteMain\trefs/heads/main" in refs, refs)
    }

    @Test
    fun `branch protection infrastructure failure is returned as a normal Git rejection`() {
        val repository = newRepository(Visibility.PUBLIC)
        val origin = seedRepository(repository)
        gitOk(origin, "checkout", "-b", "kjb/test")
        origin.resolve("feature.txt").toFile().writeText("feature work\n")
        commitAll(origin, "Feature work")
        branchProtectionFailures[repository.id] =
            IllegalStateException("restrictPushAccess is required")

        val push = git(origin, "push", authUrl(repository), "kjb/test")

        assertNotEquals(0, push.exitCode, push.output)
        assertTrue(GitPreReceiveHook.VALIDATION_UNAVAILABLE_MESSAGE in push.output, push.output)
        assertFalse("RPC failed" in push.output, push.output)
        assertFalse("remote end hung up" in push.output, push.output)
    }

    @Test
    fun `private repository requires authentication to clone`() {
        val repository = newRepository(Visibility.PRIVATE)
        val origin = newWorkDir("origin")
        gitInit(origin)
        origin.resolve("SECRET.md").toFile().writeText("classified\n")
        commitAll(origin, "Initial private commit")
        gitOk(origin, "push", authUrl(repository), "main")

        val anonymousClone = git(
            space, "clone", anonymousUrl(repository), space.resolve("denied").toString(),
        )
        assertNotEquals(0, anonymousClone.exitCode, "Anonymous private clone must fail:\n${anonymousClone.output}")

        val clone = space.resolve("granted")
        gitOk(space, "clone", authUrl(repository), clone.toString())
        assertEquals("classified\n", clone.resolve("SECRET.md").toFile().readText())
    }

    // ── git CLI helpers ───────────────────────────────────────────────────

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val output: String get() = "stdout:\n$stdout\nstderr:\n$stderr"
    }

    private fun git(dir: Path, vararg args: String): ProcessResult {
        val stdoutFile = Files.createTempFile(space, "git-out", ".txt").toFile()
        val stderrFile = Files.createTempFile(space, "git-err", ".txt").toFile()
        try {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .apply {
                    environment()["HOME"] = homeDir.toString()
                    environment()["GIT_CONFIG_GLOBAL"] = homeDir.resolve(".gitconfig").toString()
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("git ${args.joinToString(" ")} timed out after ${COMMAND_TIMEOUT_SECONDS}s")
            }
            return ProcessResult(process.exitValue(), stdoutFile.readText(), stderrFile.readText())
        } finally {
            stdoutFile.delete()
            stderrFile.delete()
        }
    }

    /** Runs a git command that is expected to succeed, failing the test with full output if it doesn't. */
    private fun gitOk(dir: Path, vararg args: String): ProcessResult {
        val result = git(dir, *args)
        if (result.exitCode != 0) {
            fail("git ${args.joinToString(" ")} failed with exit ${result.exitCode}\n${result.output}")
        }
        return result
    }

    private fun gitInit(dir: Path) {
        gitOk(dir, "init", "--initial-branch=main")
    }

    private fun commitAll(dir: Path, message: String) {
        gitOk(dir, "add", "--all")
        gitOk(dir, "commit", "-m", message)
    }

    private fun revParseHead(dir: Path): String = gitOk(dir, "rev-parse", "HEAD").stdout.trim()

    private fun newWorkDir(name: String): Path = Files.createDirectories(space.resolve(name))

    /** Creates an origin work dir with one commit already pushed to [repository]. */
    private fun seedRepository(repository: Repository): Path {
        val origin = newWorkDir("origin")
        gitInit(origin)
        origin.resolve("README.md").toFile().writeText("# ${repository.slug}\n")
        commitAll(origin, "Initial commit")
        gitOk(origin, "push", authUrl(repository), "main")
        return origin
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val OWNER = "acme"
        private const val USERNAME = "alice"
        private const val PASSWORD = "s3cret-e2e"
        private const val COMMAND_TIMEOUT_SECONDS = 120L

        // ── Shared in-memory DFS backing (namespaced by repository id) ────
        private val storageAdapter = TestDfsStorageAdapter()
        private val refAdapter = TestDfsRefAdapter()

        /** Repositories minted by tests, resolvable by slug through the mocked [RepositoryService]. */
        private val repositories = ConcurrentHashMap<String, Repository>()
        private val branchProtectionRules = ConcurrentHashMap<UUID, List<BranchProtectionRule>>()
        private val branchProtectionFailures = ConcurrentHashMap<UUID, RuntimeException>()
        private val repositoryCounter = AtomicInteger()
        private val lfsObjects = ConcurrentHashMap<Pair<UUID, String>, LfsObject>()
        private lateinit var lfsService: LfsObjectService
        private val ownerProfileId = UUID.random()

        private val principal = AuthenticatedPrincipal(
            Principal(id = UUID.random(), verified = true, anonymous = false),
            emptyList(),
        )

        private val connectionPool = mockk<ConnectionPool>(relaxed = true)
        private val repositoryService = mockk<RepositoryService>(relaxed = true)
        private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
        private val branchProtectionService = mockk<BranchProtectionService>()
        private val refUpdateNotifier = mockk<RefUpdateNotifier>(relaxed = true)
        private val securityService = mockk<SecurityService>()

        private val dfsManager = object : BoscaDfsRepositoryManager(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        ) {
            override fun open(repositoryId: UUID): DfsRepository =
                BoscaDfsRepositoryBuilder().apply {
                    this.repositoryId = repositoryId
                    storageAdapter = EndToEndGitCliTest.storageAdapter
                    refAdapter = EndToEndGitCliTest.refAdapter
                    repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
                }.build()
        }

        private val lockFactory = InMemoryLockFactory()

        /** The REAL initializer, exactly as invoked by RepositoryServiceImpl.create. */
        private val repositoryInitializer = RepositoryInitializerImpl(dfsManager, lockFactory)

        private lateinit var engine: NettyServerEngine
        private var port = 0
        private lateinit var homeDir: Path
        private lateinit var workRoot: Path

        @JvmStatic
        @BeforeClass
        fun startServer() {
            requireGitCli()
            homeDir = Files.createTempDirectory("git-e2e-home")
            workRoot = Files.createTempDirectory("git-e2e-work")
            writeGlobalGitConfig()

            ProviderRegistry.clear()
            provides<ConnectionPool>(singleton = true) { connectionPool }
            provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
            provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
            provides<AuthenticationProviders>(singleton = true) {
                AuthenticationProviders(arrayOf("basic", "api_token", "bearer", "session"))
            }

            stubDomainServices()
            val lfsRepository = mockk<LfsObjectRepository>()
            coEvery { lfsRepository.findByOid(any(), any()) } answers { lfsObjects[firstArg<UUID>() to secondArg<String>()] }
            coEvery { lfsRepository.create(any()) } answers {
                val obj = firstArg<LfsObject>().copy(id = UUID.random())
                if (lfsObjects.putIfAbsent(obj.repositoryId to obj.oid, obj) == null) obj else null
            }
            lfsService = LfsObjectServiceImpl(
                lfsRepository,
                FileSystemObjectStorageService("", "", mockk(), workRoot.resolve("lfs-objects").toString(), mockk()),
            )
            registerRoutes()

            port = freePort()
            val config = ApplicationConfig.load(
                """
                bosca:
                  server:
                    worker-threads: 2
                    codec-threads: 1
                app:
                  url: https://studio.example.com
                git:
                  url: http://127.0.0.1:$port
                """.trimIndent().byteInputStream(),
            )
            val app = BoscaApplication(config)
            app.installAuth(productionAuthMiddleware())
            runBlocking { app.configureGitRoutes() }

            engine = NettyServerEngine(app, port)
            // NEVER call engine.stop() from a test: it ends with Runtime.halt(0)
            // (production shutdown guarantee) and would kill the test JVM. The
            // daemon thread dies with the Gradle test worker instead.
            thread(isDaemon = true, name = "git-e2e-netty") { engine.start() }
            awaitServerUp()
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            ProviderRegistry.clear()
            homeDir.toFile().deleteRecursively()
            workRoot.toFile().deleteRecursively()
        }

        private fun newRepository(visibility: Visibility): Repository {
            val slug = "repo-${repositoryCounter.incrementAndGet()}"
            val repository = Repository(
                id = UUID.random(),
                slug = slug,
                name = slug,
                ownerId = ownerProfileId,
                visibility = visibility,
            )
            repositories[slug] = repository
            // Mirror RepositoryServiceImpl.create: every repository runs through the
            // real initializer, which links HEAD to the default branch even when no
            // initial commit is requested (required for --single-branch/--depth clones).
            runBlocking {
                repositoryInitializer.initialize(
                    repository.id,
                    CreateRepositoryInput(
                        slug = slug,
                        name = slug,
                        ownerId = ownerProfileId,
                        visibility = visibility,
                    ),
                )
            }
            return repository
        }

        private fun url(repository: Repository, credentials: String? = null): String {
            val userInfo = credentials?.let { "$it@" } ?: ""
            return "http://${userInfo}127.0.0.1:$port/$OWNER/${repository.slug}.git"
        }

        private fun anonymousUrl(repository: Repository) = url(repository)

        private fun authUrl(repository: Repository) = url(repository, "$USERNAME:$PASSWORD")

        private fun stubDomainServices() {
            coEvery { repositoryService.findByOwnerAndSlug(any(), any()) } coAnswers {
                val owner = firstArg<String>()
                val slug = secondArg<String>()
                if (owner == OWNER) repositories[slug] else null
            }
            // Production semantics, decided per call from the live authentication
            // context: anonymous callers may VIEW public repositories; everything
            // else requires an authenticated principal.
            coEvery { permissionEvaluator.verifyAllowed(any(), any<Repository>(), any()) } coAnswers {
                val context = firstArg<AuthenticationContext?>()
                val repository = secondArg<Repository>()
                val action = thirdArg<PermissionAction>()
                val anonymousAllowed = action == PermissionAction.VIEW && repository.visibility == Visibility.PUBLIC
                if (!anonymousAllowed && context?.principal() == null) {
                    throw SecurityException("Authentication required")
                }
            }
            coEvery { branchProtectionService.findMatchingRule(any(), any()) } coAnswers {
                val repositoryId = firstArg<UUID>()
                val branchName = secondArg<String>()
                branchProtectionFailures[repositoryId]?.let { throw it }
                branchProtectionRules[repositoryId]
                    .orEmpty()
                    .firstOrNull { BranchProtectionService.matchesGlob(it.pattern, branchName) }
            }
            coEvery { securityService.authenticateWithCredential(any()) } coAnswers {
                val attributes = firstArg<SimplePasswordAttributes>()
                if (attributes.identifier == USERNAME && attributes.password == PASSWORD) {
                    principal
                } else {
                    throw SecurityException("Invalid credentials")
                }
            }
        }

        private fun registerRoutes() {
            val preReceiveHook = GitPreReceiveHook(branchProtectionService)
            val postReceiveHook = GitPostReceiveHook(refUpdateNotifier)
            // Rate limiting is covered by GitReceivePackRouteTest; here the counter is a
            // no-op stub (increment returns 0), so real end-to-end pushes are never throttled.
            val rateLimiterConfig = mockk<ApplicationConfig>(relaxed = true)
            every { rateLimiterConfig.propertyOrNull(any()) } returns null
            val rateLimiter = PushRateLimiterImpl(mockk(relaxed = true), BoscaApplication(rateLimiterConfig))

            provides<GitInfoRefsRoute>(singleton = true) {
                GitInfoRefsRoute(repositoryService, dfsManager, permissionEvaluator)
            }
            provides<GitRepositoryRedirectRoute>(singleton = true) {
                GitRepositoryRedirectRoute(repositoryService, bosca.di.provide())
            }
            provides<GitUploadPackRoute>(singleton = true) {
                GitUploadPackRoute(repositoryService, dfsManager, permissionEvaluator)
            }
            provides<GitReceivePackRoute>(singleton = true) {
                GitReceivePackRoute(
                    repositoryService, dfsManager, permissionEvaluator,
                    preReceiveHook, postReceiveHook, rateLimiter, lockFactory,
                )
            }
            provides<GitRawFileRoute>(singleton = true) {
                GitRawFileRoute(repositoryService, dfsManager, permissionEvaluator)
            }
            provides<GitLfsBatchRoute>(singleton = true) {
                GitLfsBatchRoute(repositoryService, permissionEvaluator, lfsService, bosca.di.provide())
            }
            provides<GitLfsUploadRoute>(singleton = true) {
                GitLfsUploadRoute(repositoryService, permissionEvaluator, lfsService)
            }
            provides<GitLfsDownloadRoute>(singleton = true) {
                GitLfsDownloadRoute(repositoryService, permissionEvaluator, lfsService)
            }
            provides<GitLfsVerifyRoute>(singleton = true) {
                GitLfsVerifyRoute(repositoryService, permissionEvaluator, lfsService)
            }
            provides<TeamCityWebhookRoute>(singleton = true) {
                TeamCityWebhookRoute(mockk(relaxed = true), repositoryService, permissionEvaluator)
            }
        }

        /**
         * The REAL [BoscaAuthMiddleware]: its Basic `Authorization` header parsing,
         * error mapping, and optional-vs-required 401 behavior all run unmodified.
         * Only the credential check at the bottom ([SecurityService]) is stubbed.
         */
        private fun productionAuthMiddleware() = BoscaAuthMiddleware(
            securityConfiguration = mockk(relaxed = true),
            connectionPool = connectionPool,
            securityService = securityService,
            apiTokenService = mockk(relaxed = true),
            tracer = OpenTelemetry.noop().getTracer("git-e2e"),
            cookieMaxAge = 3600,
            errorCapture = mockk(relaxed = true),
        )

        private fun writeGlobalGitConfig() {
            homeDir.resolve(".gitconfig").toFile().writeText(
                """
                [user]
                    name = E2E Tester
                    email = e2e@bosca.io
                [init]
                    defaultBranch = main
                [advice]
                    detachedHead = false
                """.trimIndent() + "\n",
            )
        }

        private fun requireGitCli() {
            val available = try {
                ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
            check(available) { "The `git` CLI is required for the end-to-end suite but was not found on PATH" }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun awaitServerUp() {
            repeat(100) {
                try {
                    Socket("127.0.0.1", port).use { return }
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
            }
            fail("Netty server did not start on port $port")
        }
    }

    /**
     * Single-JVM [DistributedLockFactory] with real mutual exclusion, standing in
     * for the Redis/NATS-backed factory so [GitReceivePackRoute]'s write-lock and
     * renew-fence paths run for real.
     */
    private class InMemoryLockFactory : DistributedLockFactory {
        private val holders = ConcurrentHashMap<String, Any>()

        override suspend fun create(name: String): DistributedLock = InMemoryLock(name, holders)

        override suspend fun forceRelease(name: String): Boolean = holders.remove(name) != null
    }

    private class InMemoryLock(
        private val name: String,
        private val holders: ConcurrentHashMap<String, Any>,
    ) : DistributedLock {
        private val token = Any()

        override val isHeld: Boolean get() = holders[name] === token

        override suspend fun tryAcquire(ttlMillis: Long): Boolean = holders.putIfAbsent(name, token) == null

        override suspend fun acquire(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long): Boolean {
            if (tryAcquire(ttlMillis)) return true
            var waited = 0L
            while (waitTimeoutMillis == null || waited < waitTimeoutMillis) {
                delay(retryDelayMillis)
                waited += retryDelayMillis
                if (tryAcquire(ttlMillis)) return true
            }
            return false
        }

        override suspend fun renew(ttlMillis: Long): Boolean = isHeld

        override suspend fun release(): Boolean = holders.remove(name, token)

        override suspend fun <T> withLock(
            ttlMillis: Long,
            waitTimeoutMillis: Long?,
            retryDelayMillis: Long,
            block: suspend () -> T,
        ): T? {
            if (!acquire(ttlMillis, waitTimeoutMillis, retryDelayMillis)) return null
            try {
                return block()
            } finally {
                release()
            }
        }
    }
}
