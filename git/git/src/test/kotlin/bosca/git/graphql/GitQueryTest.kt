package bosca.git.graphql

import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.QuerySourceRef
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.RepositorySearchResult
import bosca.git.model.ScriptSourceRef
import bosca.git.model.Visibility
import bosca.git.model.Webhook
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.git.repository.TaskPullRequestReferenceRepository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.BranchProtectionService
import bosca.git.service.CommitStatusService
import bosca.git.service.DiffService
import bosca.git.service.PullRequestService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.git.service.SourceRefService
import bosca.git.service.WebhookService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Coverage for [GitQuery]: every query resolves its repository, enforces
 * VIEW/MANAGE, and delegates; missing repositories throw; nullable-lookup
 * queries return null; list filters and paging defaults are applied.
 */
class GitQueryTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
    private val pullRequestService = mockk<PullRequestService>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val sourceRefService = mockk<SourceRefService>(relaxed = true)
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)
    private val diffService = mockk<DiffService>(relaxed = true)
    private val taskCommitRefRepository = mockk<TaskCommitReferenceRepository>(relaxed = true)
    private val taskPrRefRepository = mockk<TaskPullRequestReferenceRepository>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    private val query = GitQuery(
        repositoryService, branchProtectionService, pullRequestService, browseService,
        webhookService, sourceRefService, commitStatusService, diffService,
        taskCommitRefRepository, taskPrRefRepository, permissionEvaluator,
    )

    private val id = UUID.random()
    private val repo = Repository(id = id, slug = "r", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE)

    @Test
    fun `git query root marker is available`() {
        assertEquals(Git, Git)
    }

    private fun repoFound() {
        coEvery { repositoryService.findById(id) } returns repo
    }

    // ── single-repository lookups ───────────────────────────────────────

    @Test
    fun `repository returns null when not found and verifies VIEW when found`() = runTest {
        coEvery { repositoryService.findByOwnerAndSlug("o", "missing") } returns null
        assertNull(query.repository(auth, "o", "missing"))

        coEvery { repositoryService.findByOwnerAndSlug("o", "r") } returns repo
        assertEquals(repo, query.repository(auth, "o", "r"))
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
    }

    @Test
    fun `repositoryById returns null when not found`() = runTest {
        coEvery { repositoryService.findById(id) } returns null
        assertNull(query.repositoryById(auth, id))

        repoFound()
        assertEquals(repo, query.repositoryById(auth, id))
    }

    // ── repositories() dispatch arms ────────────────────────────────────

    @Test
    fun `repositories dispatches on ownerId and contentType combinations`() = runTest {
        val ownerId = UUID.random()
        coEvery { permissionEvaluator.filterAllowed(auth, any<List<Repository>>(), PermissionAction.VIEW) } answers { thirdArg<Any>(); secondArg() }

        query.repositories(auth, ownerId, RepositoryContentType.GENERAL, true)
        coVerify { repositoryService.findByOwner(ownerId, true) }

        query.repositories(auth, null, RepositoryContentType.GENERAL, null)
        coVerify { repositoryService.findByContentType(RepositoryContentType.GENERAL) }

        query.repositories(auth, ownerId, null, null)
        coVerify { repositoryService.findByOwner(ownerId, false) }

        query.repositories(auth, null, null, null)
        coVerify { repositoryService.findAll(false) }
    }

    @Test
    fun `repositories with owner and contentType filters the owner list by type`() = runTest {
        val ownerId = UUID.random()
        val script = repo.copy(id = UUID.random(), contentType = RepositoryContentType.SCRIPT_PROJECT)
        val general = repo.copy(id = UUID.random(), contentType = RepositoryContentType.GENERAL)
        coEvery { repositoryService.findByOwner(ownerId, false) } returns listOf(script, general)
        coEvery { permissionEvaluator.filterAllowed(auth, any<List<Repository>>(), PermissionAction.VIEW) } answers { secondArg() }

        val result = query.repositories(auth, ownerId, RepositoryContentType.SCRIPT_PROJECT, null)
        assertEquals(listOf(script), result)
    }

    // ── delegating browse queries ───────────────────────────────────────

    @Test
    fun `browse queries verify VIEW and delegate with defaults`() = runTest {
        repoFound()

        query.tree(auth, id, null, null)
        coVerify { browseService.listTree(id, "HEAD", null) }

        query.blob(auth, id, "main", "a.txt")
        coVerify { browseService.readBlob(id, "main", "a.txt") }

        query.commits(auth, id, null, null, null, null)
        coVerify { browseService.listCommits(id, "HEAD", null, 25, 0) }

        query.commits(auth, id, "dev", "src", 500, 10)
        coVerify { browseService.listCommits(id, "dev", "src", 100, 10) } // limit coerced to 100

        query.commit(auth, id, "abc")
        coVerify { browseService.getCommit(id, "abc") }

        query.branches(auth, id)
        coVerify { browseService.listBranches(id) }

        query.tags(auth, id)
        coVerify { browseService.listTags(id) }

        query.blame(auth, id, "main", "a.txt")
        coVerify { browseService.blame(id, "main", "a.txt") }

        query.stats(auth, id)
        coVerify { browseService.getStats(id) }

        query.compare(auth, id, "main", "dev")
        coVerify { browseService.compare(id, "main", "dev", diffService) }

        query.searchPaths(auth, id, "q", null)
        coVerify { browseService.searchPaths(id, "q", "HEAD") }

        query.searchContent(auth, id, "q", null, null)
        coVerify { browseService.searchContent(id, "q", "HEAD", 20) }

        query.commitStatuses(auth, id, "abc")
        coVerify { commitStatusService.getStatuses(id, "abc") }

        query.taskCommitReferences(auth, id, "T-1")
        coVerify { taskCommitRefRepository.findByTaskKey(id, "T-1") }

        query.taskPullRequestReferences(auth, id, "T-1")
        coVerify { taskPrRefRepository.findByTaskKey(id, "T-1") }
    }

    @Test
    fun `browse queries throw when the repository is missing`() = runTest {
        coEvery { repositoryService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> { query.tree(auth, id, null, null) }
        assertFailsWith<NoSuchElementException> { query.commits(auth, id, null, null, null, null) }
        assertFailsWith<NoSuchElementException> { query.stats(auth, id) }
        assertFailsWith<NoSuchElementException> { query.branchProtectionRules(auth, id) }
    }

    // ── pull requests ───────────────────────────────────────────────────

    @Test
    fun `pullRequest and pullRequests verify VIEW and apply paging defaults`() = runTest {
        repoFound()

        query.pullRequest(auth, id, 7)
        coVerify { pullRequestService.findByNumber(id, 7) }

        query.pullRequests(auth, id, null, null, null, null)
        coVerify { pullRequestService.findByRepository(id, null, null, 0, 25) }

        query.pullRequests(auth, id, null, null, 5, 1000)
        coVerify { pullRequestService.findByRepository(id, null, null, 5, 100) } // coerced
    }

    @Test
    fun `allPullRequests filters repositories before applying query paging`() = runTest {
        val hidden = repo.copy(id = UUID.random())
        val authorId = UUID.random()
        coEvery { repositoryService.findAll(false) } returns listOf(repo, hidden)
        coEvery {
            permissionEvaluator.filterAllowed(auth, listOf(repo, hidden), PermissionAction.VIEW)
        } returns listOf(repo)

        query.allPullRequests(auth, PullRequestStatus.OPEN, authorId, 5, 1000)

        coVerify {
            pullRequestService.findByRepositories(
                listOf(repo.id), PullRequestStatus.OPEN, authorId, 5, 100
            )
        }
    }

    @Test
    fun `allPullRequests applies defaults when no repositories are visible`() = runTest {
        coEvery { repositoryService.findAll(false) } returns listOf(repo)
        coEvery {
            permissionEvaluator.filterAllowed(auth, listOf(repo), PermissionAction.VIEW)
        } returns emptyList()

        query.allPullRequests(auth, null, null, null, null)

        coVerify { pullRequestService.findByRepositories(emptyList(), null, null, 0, 25) }
    }

    @Test
    fun `pullRequestMergePlan verifies view permission for every affected repository`() = runTest {
        val dependencyRepository = repo.copy(id = UUID.random(), slug = "dependency")
        val pullRequestId = UUID.random()
        val dependency = PullRequest(
            id = UUID.random(), repositoryId = dependencyRepository.id, number = 2,
            title = "Dependency", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        val parent = dependency.copy(id = pullRequestId, repositoryId = id, number = 3, title = "Parent")
        coEvery { pullRequestService.findById(pullRequestId) } returns parent
        coEvery { pullRequestService.getMergePlan(pullRequestId) } returns listOf(dependency, parent)
        coEvery { repositoryService.findById(id) } returns repo
        coEvery { repositoryService.findById(dependencyRepository.id) } returns dependencyRepository
        coEvery {
            permissionEvaluator.filterAllowed(
                auth, listOf(dependencyRepository, repo), PermissionAction.VIEW,
            )
        } returns listOf(dependencyRepository, repo)

        assertEquals(listOf(dependency, parent), query.pullRequestMergePlan(auth, pullRequestId))
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify {
            permissionEvaluator.filterAllowed(
                auth, listOf(dependencyRepository, repo), PermissionAction.VIEW,
            )
        }
    }

    @Test
    fun `pullRequestMergePlan rejects the whole plan when a dependency repository is hidden`() = runTest {
        val hiddenRepository = repo.copy(id = UUID.random(), slug = "hidden")
        val pullRequestId = UUID.random()
        val hidden = PullRequest(
            id = UUID.random(), repositoryId = hiddenRepository.id, number = 2,
            title = "Hidden", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        val parent = hidden.copy(id = pullRequestId, repositoryId = id, number = 3)
        coEvery { pullRequestService.findById(pullRequestId) } returns parent
        coEvery { pullRequestService.getMergePlan(pullRequestId) } returns listOf(hidden, parent)
        coEvery { repositoryService.findById(id) } returns repo
        coEvery { repositoryService.findById(hiddenRepository.id) } returns hiddenRepository
        coEvery {
            permissionEvaluator.filterAllowed(auth, listOf(hiddenRepository, repo), PermissionAction.VIEW)
        } returns listOf(repo)

        val failure = assertFailsWith<SecurityException> {
            query.pullRequestMergePlan(auth, pullRequestId)
        }
        assertEquals("You cannot view every pull request required for this merge", failure.message)
    }

    @Test
    fun `pullRequestMergePlan rejects missing pull requests and repositories`() = runTest {
        val pullRequestId = UUID.random()
        coEvery { pullRequestService.findById(pullRequestId) } returns null
        assertFailsWith<NoSuchElementException> {
            query.pullRequestMergePlan(auth, pullRequestId)
        }

        val parent = PullRequest(
            id = pullRequestId, repositoryId = id, number = 1, title = "Parent",
            authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
        )
        coEvery { pullRequestService.findById(pullRequestId) } returns parent
        coEvery { repositoryService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            query.pullRequestMergePlan(auth, pullRequestId)
        }

        coEvery { repositoryService.findById(id) } returns repo
        coEvery { pullRequestService.getMergePlan(pullRequestId) } returns listOf(parent)
        coEvery { repositoryService.findById(id) } returnsMany listOf(repo, null)
        val failure = assertFailsWith<SecurityException> {
            query.pullRequestMergePlan(auth, pullRequestId)
        }
        assertEquals("You cannot view every pull request required for this merge", failure.message)
    }

    // ── MANAGE-gated queries ────────────────────────────────────────────

    @Test
    fun `admin queries require MANAGE`() = runTest {
        repoFound()

        query.branchProtectionRules(auth, id)
        query.permissions(auth, id)
        query.webhooks(auth, id)

        coVerify(exactly = 3) { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.MANAGE) }
        coVerify { branchProtectionService.findByRepository(id) }
        coVerify { webhookService.findByRepository(id) }
    }

    @Test
    fun `webhookDeliveries resolves the webhook then its repository`() = runTest {
        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns
            Webhook(id = hookId, repositoryId = id, url = "https://x", secret = "s")
        repoFound()

        query.webhookDeliveries(auth, hookId, null, null)
        coVerify { webhookService.getDeliveries(hookId, 0, 25) }

        coEvery { webhookService.findById(hookId) } returns null
        assertFailsWith<NoSuchElementException> { query.webhookDeliveries(auth, hookId, null, null) }
    }

    // ── source refs ─────────────────────────────────────────────────────

    @Test
    fun `source ref queries return null when no ref exists`() = runTest {
        val scriptId = UUID.random()
        val queryId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns null
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns null
        assertNull(query.scriptSourceRef(auth, scriptId))
        assertNull(query.querySourceRef(auth, queryId))
    }

    @Test
    fun `source ref queries verify VIEW on the owning repository`() = runTest {
        val scriptId = UUID.random()
        val queryId = UUID.random()
        repoFound()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns ScriptSourceRef(scriptId, id, "p")
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns QuerySourceRef(queryId, id, "p")

        query.scriptSourceRef(auth, scriptId)
        query.querySourceRef(auth, queryId)
        query.sourceRefs(auth, id)

        coVerify(exactly = 3) { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify { sourceRefService.findSourceRefsByRepository(id) }
    }

    // ── search ──────────────────────────────────────────────────────────

    @Test
    fun `searchCode verifies VIEW only when scoped to a repository`() = runTest {
        query.searchCode(auth, "q", null, null, null, null)
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(auth, any(), any()) }
        coVerify { browseService.searchCode("q", null, null, 0, 20) }

        repoFound()
        query.searchCode(auth, "q", id, "kotlin", 5, 500)
        coVerify { permissionEvaluator.verifyAllowed(auth, repo, PermissionAction.VIEW) }
        coVerify { browseService.searchCode("q", id, "kotlin", 5, 100) }
    }

    @Test
    fun `searchRepositories filters results to permitted repositories`() = runTest {
        val visibleId = UUID.random()
        val hiddenId = UUID.random()
        fun result(repositoryId: UUID) = RepositorySearchResult(
            id = repositoryId,
            name = "n",
            slug = "s",
            description = "",
            ownerId = UUID.random(),
            visibility = "PRIVATE",
            defaultBranch = "main",
            archived = false,
        )
        coEvery { browseService.searchRepositories("q", null, null, 0, 20) } returns
            RepositorySearchResponse(listOf(result(visibleId), result(hiddenId)), 2)
        val visibleRepository = repo.copy(id = visibleId)
        val hiddenRepository = repo.copy(id = hiddenId)
        coEvery { repositoryService.findById(visibleId) } returns visibleRepository
        coEvery { repositoryService.findById(hiddenId) } returns hiddenRepository
        coEvery {
            permissionEvaluator.filterAllowed(auth, any<List<Repository>>(), PermissionAction.VIEW)
        } returns listOf(visibleRepository)

        val response = query.searchRepositories(auth, "q", null, null, null, null)

        assertEquals(listOf(visibleId), response.results.map { it.id })
        assertEquals(1L, response.estimatedHits)
    }

    @Test
    fun `every repository-scoped query throws when the repository is missing`() = runTest {
        coEvery { repositoryService.findById(id) } returns null
        val scriptId = UUID.random()
        val queryId = UUID.random()
        coEvery { sourceRefService.findScriptSourceRef(scriptId) } returns ScriptSourceRef(scriptId, id, "p")
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns QuerySourceRef(queryId, id, "p")
        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns Webhook(id = hookId, repositoryId = id, url = "u", secret = "s")

        val calls: List<Pair<String, suspend () -> Any?>> = listOf(
            "pullRequest" to { query.pullRequest(auth, id, 1) },
            "pullRequests" to { query.pullRequests(auth, id, null, null, null, null) },
            "blob" to { query.blob(auth, id, "main", "a") },
            "commit" to { query.commit(auth, id, "abc") },
            "branches" to { query.branches(auth, id) },
            "tags" to { query.tags(auth, id) },
            "blame" to { query.blame(auth, id, "main", "a") },
            "permissions" to { query.permissions(auth, id) },
            "webhooks" to { query.webhooks(auth, id) },
            "webhookDeliveries" to { query.webhookDeliveries(auth, hookId, null, null) },
            "scriptSourceRef" to { query.scriptSourceRef(auth, scriptId) },
            "querySourceRef" to { query.querySourceRef(auth, queryId) },
            "sourceRefs" to { query.sourceRefs(auth, id) },
            "compare" to { query.compare(auth, id, "a", "b") },
            "searchPaths" to { query.searchPaths(auth, id, "q", null) },
            "searchCode" to { query.searchCode(auth, "q", id, null, null, null) },
            "searchContent" to { query.searchContent(auth, id, "q", null, null) },
            "commitStatuses" to { query.commitStatuses(auth, id, "abc") },
            "taskCommitReferences" to { query.taskCommitReferences(auth, id, "T-1") },
            "taskPullRequestReferences" to { query.taskPullRequestReferences(auth, id, "T-1") },
        )
        for ((name, block) in calls) {
            try {
                block()
                kotlin.test.fail("$name should have thrown NoSuchElementException")
            } catch (_: NoSuchElementException) { /* expected */ }
        }
    }

    @Test
    fun `explicit paging and refs bypass the defaults`() = runTest {
        repoFound()
        coEvery { permissionEvaluator.filterAllowed(auth, any<List<Repository>>(), PermissionAction.VIEW) } answers { secondArg() }

        query.repositories(auth, UUID.random(), null, false) // non-null includeArchived
        query.tree(auth, id, "dev", "src")
        coVerify { browseService.listTree(id, "dev", "src") }
        query.searchPaths(auth, id, "q", "dev")
        coVerify { browseService.searchPaths(id, "q", "dev") }
        query.searchContent(auth, id, "q", "dev", 5)
        coVerify { browseService.searchContent(id, "q", "dev", 5) }
        query.webhookDeliveries(auth, hookIdWithRepo(), 3, 7)
        coVerify { webhookService.getDeliveries(any(), 3, 7) }
    }

    private fun hookIdWithRepo(): UUID {
        val hookId = UUID.random()
        coEvery { webhookService.findById(hookId) } returns Webhook(id = hookId, repositoryId = id, url = "u", secret = "s")
        return hookId
    }
}
