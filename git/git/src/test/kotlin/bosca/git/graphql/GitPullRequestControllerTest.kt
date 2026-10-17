package bosca.git.graphql

import bosca.git.model.MergeResult
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.service.DiffService
import bosca.git.service.PullRequestService
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.graphql.parser.Parser
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.GraphQLExecutor
import bosca.graphql.server.runtimeWiring
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [GitPullRequestController]: field pass-throughs plus the computed
 * resolvers (mergeability gating on OPEN status, assignee profile resolution
 * tolerating missing profiles, and the branch-to-branch diff).
 */
class GitPullRequestControllerTest {

    private val pullRequestService = mockk<PullRequestService>(relaxed = true)
    private val diffService = mockk<DiffService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val controller = GitPullRequestController(
        pullRequestService, diffService, profileService, repositoryService, permissionEvaluator,
    )

    private val pr = PullRequest(
        id = UUID.random(), repositoryId = UUID.random(), number = 7, title = "T",
        description = "d", authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
    )

    @Test
    fun `simple fields pass through the source`() {
        assertEquals(pr.id, controller.id(pr))
        assertEquals(pr.repositoryId, controller.repositoryId(pr))
        assertEquals(7, controller.number(pr))
        assertEquals("T", controller.title(pr))
        assertEquals("d", controller.description(pr))
        assertEquals(pr.authorId, controller.authorId(pr))
        assertEquals("feature", controller.sourceBranch(pr))
        assertEquals("main", controller.targetBranch(pr))
        assertEquals(null, controller.sourceRepositoryId(pr))
        assertEquals(PullRequestStatus.OPEN, controller.status(pr))
        assertTrue(controller.deleteBranchOnMerge(pr))
        assertFalse(controller.deleteBranchOnMerge(pr.copy(deleteBranchOnMerge = false)))
        assertEquals(null, controller.mergeStrategy(pr))
        assertEquals(null, controller.mergedBy(pr))
        assertEquals(null, controller.mergedAt(pr))
        assertEquals(null, controller.mergeSha(pr))
        assertEquals(pr.created, controller.created(pr))
        assertEquals(pr.updated, controller.updated(pr))
    }

    @Test
    fun `mergeable consults the service only for open pull requests`() = runTest {
        coEvery { pullRequestService.checkMergeability(pr.id) } returns MergeResult(success = true)
        assertTrue(mergeFields(pr).getValue("mergeable").jsonPrimitive.boolean)

        assertFalse(mergeFields(pr.copy(status = PullRequestStatus.MERGED)).getValue("mergeable").jsonPrimitive.boolean)
        coVerify(exactly = 1) { pullRequestService.checkMergeability(any()) }
    }

    @Test
    fun `conflictingFiles is empty for closed pull requests`() = runTest {
        coEvery { pullRequestService.checkMergeability(pr.id) } returns
            MergeResult(success = false, conflictingFiles = listOf("a.txt"))
        assertEquals(listOf("a.txt"), mergeFields(pr).getValue("conflictingFiles").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(mergeFields(pr.copy(status = PullRequestStatus.CLOSED)).getValue("conflictingFiles").jsonArray.isEmpty())
    }

    @Test
    fun `merge fields and aliases share one calculation and refresh on the next request`() = runTest {
        coEvery { pullRequestService.checkMergeability(pr.id) } returnsMany listOf(
            MergeResult(success = true),
            MergeResult(success = false, conflictingFiles = listOf("changed.txt")),
        )
        val first = mergeFields(pr)
        assertTrue(first.getValue("mergeable").jsonPrimitive.boolean)
        assertEquals(first["mergeable"], first["again"])
        assertTrue(first.getValue("conflictingFiles").jsonArray.isEmpty())
        coVerify(exactly = 1) { pullRequestService.checkMergeability(pr.id) }

        val next = mergeFields(pr)
        assertFalse(next.getValue("mergeable").jsonPrimitive.boolean)
        assertEquals(listOf("changed.txt"), next.getValue("conflictingFiles").jsonArray.map { it.jsonPrimitive.content })
        coVerify(exactly = 2) { pullRequestService.checkMergeability(pr.id) }
    }

    private suspend fun mergeFields(source: PullRequest): JsonObject {
        val executor = GraphQLExecutor(ExecutableSchema.fromSdl(
            """
                type Query { pull: GitPullRequest! }
                type GitPullRequest { mergeable: Boolean!, conflictingFiles: [String!]! }
            """.trimIndent(),
            runtimeWiring {
                type("Query") { field("pull") { source } }
                type("GitPullRequest") {
                    field("mergeable") { environment -> controller.mergeable(source, environment) }
                    field("conflictingFiles") { environment -> controller.conflictingFiles(source, environment) }
                }
            },
        ))
        val result = executor.execute(Parser.parse("{ pull { mergeable conflictingFiles again: mergeable } }"))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        return result.data?.jsonObject?.getValue("pull")?.jsonObject ?: error("Missing pull request result")
    }

    @Test
    fun `assignees resolves profiles and drops failures`() = runTest {
        val ok = UUID.random()
        val bad = UUID.random()
        coEvery { pullRequestService.getAssignees(pr.id) } returns listOf(ok, bad)
        val profile = mockk<bosca.profile.model.Profile>(relaxed = true)
        coEvery { profileService.getById(ok) } returns profile
        coEvery { profileService.getById(bad) } throws IllegalStateException("gone")

        assertEquals(listOf(ok, bad), controller.assigneeIds(pr))
        assertEquals(listOf(profile), controller.assignees(pr))
    }

    @Test
    fun `reviews and diff delegate to their services`() = runTest {
        controller.reviews(pr)
        coVerify { pullRequestService.getReviews(pr.id) }

        controller.diff(pr)
        coVerify { diffService.computeDiff(pr.repositoryId, "refs/heads/main", "refs/heads/feature") }
    }

    @Test
    fun `merged diffs use the immutable merge comparison instead of branch tips`() = runTest {
        val merged = pr.copy(status = PullRequestStatus.MERGED, mergeSha = "merged", mergeBaseSha = "before")
        coEvery { diffService.resolveMergedBase(merged) } returns "before"
        assertEquals("before", controller.mergeBaseSha(merged))
        controller.diff(merged)
        coVerify { diffService.computeDiff(pr.repositoryId, "before", "merged") }
        coVerify(exactly = 0) { diffService.computeDiff(any(), "refs/heads/main", any()) }
    }

    @Test
    fun `commit list and selected diffs share the PR comparison and pagination`() = runTest {
        controller.commits(pr, 50, 26)
        coVerify { diffService.listComparisonCommits(pr.repositoryId, "refs/heads/main", "refs/heads/feature", 50, 26) }
        controller.diff(pr, "selected")
        coVerify { diffService.computeCommitDiff(pr.repositoryId, "refs/heads/main", "refs/heads/feature", "selected") }
        val merged = pr.copy(status = PullRequestStatus.MERGED, mergeSha = "merged", mergeBaseSha = "before")
        coEvery { diffService.resolveMergedBase(merged) } returns "before"
        controller.commits(merged)
        controller.diff(merged, "selected")
        coVerify { diffService.listComparisonCommits(pr.repositoryId, "before", "merged", 0, 25) }
        coVerify { diffService.computeCommitDiff(pr.repositoryId, "before", "merged", "selected") }
    }

    @Test
    fun `merged diffs require recorded head and a determinable original base`() = runTest {
        assertFailsWith<IllegalStateException> { controller.diff(pr.copy(status = PullRequestStatus.MERGED)) }
        assertFailsWith<IllegalStateException> { controller.diff(pr.copy(status = PullRequestStatus.MERGED, mergeSha = " ")) }
        val merged = pr.copy(status = PullRequestStatus.MERGED, mergeSha = "merged")
        coEvery { diffService.resolveMergedBase(merged) } returns null
        assertFailsWith<IllegalStateException> { controller.diff(merged) }
        coVerify(exactly = 0) { diffService.computeDiff(any(), any(), any()) }
    }

    @Test
    fun `dependencies and dependents only expose pull requests from visible repositories`() = runTest {
        val authentication = mockk<AuthenticationContext>(relaxed = true)
        val visibleRepository = Repository(
            id = UUID.random(), slug = "visible", name = "Visible",
            ownerId = UUID.random(), visibility = Visibility.PRIVATE,
        )
        val hiddenRepository = visibleRepository.copy(id = UUID.random(), slug = "hidden")
        val visible = pr.copy(id = UUID.random(), repositoryId = visibleRepository.id)
        val hidden = pr.copy(id = UUID.random(), repositoryId = hiddenRepository.id)
        coEvery { pullRequestService.getDependencies(pr.id) } returns listOf(visible, hidden)
        coEvery { pullRequestService.getDependents(pr.id) } returns listOf(hidden, visible)
        coEvery { repositoryService.findById(visibleRepository.id) } returns visibleRepository
        coEvery { repositoryService.findById(hiddenRepository.id) } returns hiddenRepository
        coEvery {
            permissionEvaluator.filterAllowed(
                authentication,
                listOf(visibleRepository, hiddenRepository),
                PermissionAction.VIEW,
            )
        } returns listOf(visibleRepository)
        coEvery {
            permissionEvaluator.filterAllowed(
                authentication,
                listOf(hiddenRepository, visibleRepository),
                PermissionAction.VIEW,
            )
        } returns listOf(visibleRepository)

        assertEquals(listOf(visible), controller.dependencies(authentication, pr))
        assertEquals(listOf(visible), controller.dependents(authentication, pr))
    }
}
