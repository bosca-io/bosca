package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PushRateLimiter
import bosca.git.service.RepositoryService
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [GitReceivePackRoute]: auth/scope/archived/rate-limit guards, the
 * per-repository write lock (including the 503 maintenance path), and an
 * empty-push smart-HTTP exchange through the real JGit ReceivePack.
 */
class GitReceivePackRouteTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val preReceiveHook = mockk<GitPreReceiveHook>(relaxed = true)
    private val postReceiveHook = mockk<GitPostReceiveHook>(relaxed = true)
    private val rateLimiter = mockk<PushRateLimiter>(relaxed = true)
    private val lockFactory = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>(relaxed = true)

    private val route = GitReceivePackRoute(
        repositoryService, dfsManager, permissionEvaluator,
        preReceiveHook, postReceiveHook, rateLimiter, lockFactory,
    )

    private val repositoryId = UUID.random()
    private val repository = Repository(id = repositoryId, slug = "repo", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE)
    private lateinit var gitRepo: InMemoryRepository
    private val params = mapOf("owner" to "owner", "repo" to "repo")

    /** A flush-pkt-only push: no ref commands, no pack — a valid no-op exchange. */
    private val emptyPush = GitInfoRefsRoute.pktFlush()

    @BeforeTest
    fun setup() {
        clearRouteProviders()
        registerRouteProviders()
        gitRepo = InMemoryRepository(DfsRepositoryDescription("receive"))
        every { dfsManager.open(repositoryId) } returns gitRepo
        coEvery { repositoryService.findByOwnerAndSlug("owner", "repo") } returns repository
        coEvery { rateLimiter.tryAcquire(any()) } returns true
        coEvery { lockFactory.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        clearRouteProviders()
    }

    private fun principal(): AuthenticatedPrincipal {
        val p = mockk<AuthenticatedPrincipal>(relaxed = true)
        every { p.id } returns UUID.random()
        return p
    }

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
    }

    @Test
    fun `permission denial returns 401 with a challenge`() = runTest {
        coEvery { permissionEvaluator.verifyAllowed(any(), repository, any()) } throws SecurityException("no")
        val (call, rec) = recordedCall(pathParameters = params)
        runRoute(route, call)
        assertEquals(HttpStatusCode.Unauthorized, rec.status)
        assertEquals("Basic realm=\"Bosca Git\"", rec.headers["WWW-Authenticate"])
    }

    @Test
    fun `scoped token without write scope returns 403`() = runTest {
        val scoped = mockk<ScopedAuthenticatedPrincipal>(relaxed = true)
        every { scoped.hasScope("git:write") } returns false
        val (call, rec) = recordedCall(pathParameters = params, principal = scoped)
        runRoute(route, call)
        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("git:write"))
    }

    @Test
    fun `archived repository rejects pushes with 403`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("owner", "repo") } returns repository.copy(archived = true)
        val (call, rec) = recordedCall(pathParameters = params)
        runRoute(route, call)
        assertEquals(HttpStatusCode.Forbidden, rec.status)
        assertTrue(rec.bodyText.contains("archived"))
    }

    @Test
    fun `rate-limited principal receives 429`() = runTest {
        val p = principal()
        coEvery { rateLimiter.tryAcquire(p.id) } returns false
        val (call, rec) = recordedCall(pathParameters = params, principal = p)
        runRoute(route, call)
        assertEquals(HttpStatusCode.TooManyRequests, rec.status)
        assertTrue(rec.bodyText.contains("Rate limit"))
    }

    @Test
    fun `write lock held by maintenance returns a retryable 503`() = runTest {
        coEvery { lock.acquire(any(), any(), any()) } returns false
        val (call, rec) = recordedCall(pathParameters = params, body = emptyPush)
        runRoute(route, call)
        assertEquals(HttpStatusCode.ServiceUnavailable, rec.status)
        assertTrue(rec.bodyText.contains("retry shortly"), "body=${rec.bodyText}")
    }

    @Test
    fun `empty push completes the exchange under the write lock`() = runTest {
        val p = principal()
        val attributedPostReceiveHook = mockk<GitPostReceiveHook>(relaxed = true)
        every { postReceiveHook.withInitiatingPrincipal(p.id) } returns attributedPostReceiveHook
        val (call, rec) = recordedCall(pathParameters = params, principal = p, body = emptyPush)
        runRoute(route, call)

        // The exchange streamed a receive-pack result and released the lock.
        io.mockk.coVerify { lock.acquire(any(), any(), any()) }
        io.mockk.coVerify { lock.release() }
        verify { postReceiveHook.withInitiatingPrincipal(p.id) }
        assertEquals("application", rec.contentType?.contentType)
        assertEquals("x-git-receive-pack-result", rec.contentType?.contentSubtype)
    }

    @Test
    fun `invalid receive-pack command returns a Git protocol error without truncating HTTP`() = runTest {
        val (call, rec) = recordedCall(
            pathParameters = params,
            principal = principal(),
            body = GitInfoRefsRoute.pktLine("invalid-command\n") + GitInfoRefsRoute.pktFlush(),
        )

        runRoute(route, call)

        assertEquals(HttpStatusCode.OK, rec.status)
        assertEquals("application", rec.contentType?.contentType)
        assertEquals("x-git-receive-pack-result", rec.contentType?.contentSubtype)
        assertTrue(rec.bodyText.contains("ERR "), "body=${rec.bodyText}")
    }

    @Test
    fun `unpack failure returns a Git status report without truncating HTTP`() = runTest {
        val command = "${"0".repeat(40)} ${"1".repeat(40)} refs/heads/broken\u0000 report-status side-band-64k\n"
        val request = GitInfoRefsRoute.pktLine(command) + GitInfoRefsRoute.pktFlush() +
            "not-a-pack".toByteArray()
        val (call, rec) = recordedCall(
            pathParameters = params,
            principal = principal(),
            body = request,
        )

        runRoute(route, call)

        assertEquals(HttpStatusCode.OK, rec.status)
        assertEquals("application", rec.contentType?.contentType)
        assertEquals("x-git-receive-pack-result", rec.contentType?.contentSubtype)
        assertTrue(rec.bodyText.contains("unpack error"), "body=${rec.bodyText}")
        assertTrue(rec.bodyText.contains("ng refs/heads/broken"), "body=${rec.bodyText}")
    }
}
