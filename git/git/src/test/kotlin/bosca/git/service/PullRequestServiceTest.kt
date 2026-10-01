@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.BranchProtectionRule
import bosca.git.model.CreatePullRequestInput
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.PullRequestStatus
import bosca.git.model.Repository
import bosca.git.model.Review
import bosca.git.model.ReviewStatus
import bosca.git.model.SubmitReviewInput
import bosca.git.model.UpdatePullRequestInput
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.PullRequestAssigneeRepository
import bosca.git.repository.PullRequestDependencyRepository
import bosca.git.repository.PullRequestRepository
import bosca.git.repository.PullRequestRepositoryQuery
import bosca.git.repository.ReviewCommentRepository
import bosca.git.repository.ReviewRepository
import bosca.git.repository.TaskPullRequestReferenceRepository
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.profile.profile.service.ProfileService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.pubsub.PubSubService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PullRequestServiceTest {

    private val prRepository = mockk<PullRequestRepository>(relaxed = true)
    private val dependencyRepository = mockk<PullRequestDependencyRepository>(relaxed = true)
    private val reviewRepository = mockk<ReviewRepository>(relaxed = true)
    private val reviewCommentRepository = mockk<ReviewCommentRepository>(relaxed = true)
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val assigneeRepository = mockk<PullRequestAssigneeRepository>(relaxed = true)
    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)
    private val taskPrRefRepository = mockk<TaskPullRequestReferenceRepository>(relaxed = true)
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)
    private val lockFactory = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val pubSub = mockk<PubSubService>(relaxed = true)
    private lateinit var service: PullRequestService

    private val repositoryId = UUID.random()
    private val authorId = UUID.random()
    private val prId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PubSubService>(singleton = true) { pubSub }
        coEvery { profileService.getAllByIds(any()) } answers {
            firstArg<List<UUID>>().map { id -> Profile(
                id = id,
                type = ProfileType.GENERIC,
                name = "Git collaborator",
                visibility = ProfileVisibility.USER,
            ) }
        }
        coEvery { lockFactory.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        service = PullRequestServiceImpl(
            prRepository,
            dependencyRepository,
            reviewRepository,
            reviewCommentRepository,
            repoRepository,
            assigneeRepository,
            branchProtectionService,
            dfsManager,
            taskPrRefRepository,
            commitStatusService,
            lockFactory,
            profileService,
            securityService,
        )
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun testPr(
        status: PullRequestStatus = PullRequestStatus.OPEN,
        number: Int = 1
    ) = PullRequest(
        id = prId,
        repositoryId = repositoryId,
        number = number,
        title = "Test PR",
        authorId = authorId,
        sourceBranch = "feature",
        targetBranch = "main",
        status = status
    )

    @Test
    fun `create increments PR number and persists`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 42
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }

        val input = CreatePullRequestInput(
            repositoryId = repositoryId,
            title = "Add feature",
            sourceBranch = "feature",
            targetBranch = "main"
        )
        val pr = service.create(input, authorId)
        assertEquals(42, pr.number)
        assertEquals(PullRequestStatus.OPEN, pr.status)
        assertEquals("Add feature", pr.title)
    }

    @Test
    fun `create publishes an opened event with normalized recipients and task keys`() = runTest {
        val authorProfileId = UUID.random()
        val ownerProfileId = UUID.random()
        val authorProfile = Profile(
            id = authorProfileId,
            type = ProfileType.GENERIC,
            principal = authorId,
            name = "Author",
            visibility = ProfileVisibility.USER,
        )
        val principal = mockk<Principal>()
        coEvery { profileService.getAllByIds(listOf(authorId)) } returns emptyList()
        coEvery { securityService.getPrincipalById(authorId) } returns principal
        coEvery { profileService.getPrimaryProfile(principal) } returns authorProfile
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 42
        coEvery { repoRepository.findById(repositoryId) } returns Repository(
            id = repositoryId,
            slug = "bosca",
            name = "Bosca",
            ownerId = ownerProfileId,
        )
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }

        service.create(
            CreatePullRequestInput(
                repositoryId = repositoryId,
                title = "GIT-79 send notifications",
                sourceBranch = "feature/GIT-79-email",
                targetBranch = "main",
            ),
            authorId,
        )

        coVerify {
            pubSub.publish(
                "bosca.git.pull_request",
                any<kotlinx.serialization.SerializationStrategy<PullRequestEvent>>(),
                match<PullRequestEvent> {
                    it.repositoryName == "Bosca" &&
                        it.action == PullRequestEventAction.OPENED &&
                        it.authorId == authorProfileId &&
                        it.actorId == authorProfileId &&
                        it.actorName == "Author" &&
                        it.recipientIds == setOf(authorProfileId, ownerProfileId) &&
                        it.taskKeys == setOf("GIT-79")
                },
            )
        }
    }

    @Test
    fun `create with isDraft sets DRAFT status`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 1
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }

        val input = CreatePullRequestInput(
            repositoryId = repositoryId,
            title = "WIP",
            sourceBranch = "wip",
            targetBranch = "main",
            isDraft = true
        )
        val pr = service.create(input, authorId)
        assertEquals(PullRequestStatus.DRAFT, pr.status)
    }

    @Test
    fun `update changes title and description`() = runTest {
        val existing = testPr()
        coEvery { prRepository.findById(prId) } returns existing
        coEvery { prRepository.update(any()) } answers { firstArg() }

        val updated = service.update(prId, UpdatePullRequestInput(title = "New Title"))
        assertEquals("New Title", updated.title)
    }

    @Test
    fun `close transitions OPEN to CLOSED`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.OPEN)
        coEvery { prRepository.updateStatus(prId, PullRequestStatus.CLOSED) } returns testPr(status = PullRequestStatus.CLOSED)

        val closed = service.close(prId)
        assertEquals(PullRequestStatus.CLOSED, closed.status)
    }

    @Test
    fun `close rejects MERGED PR`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.MERGED)
        assertFailsWith<IllegalArgumentException> { service.close(prId) }
    }

    @Test
    fun `reopen transitions CLOSED to OPEN`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.CLOSED)
        coEvery { prRepository.updateStatus(prId, PullRequestStatus.OPEN) } returns testPr(status = PullRequestStatus.OPEN)

        val reopened = service.reopen(prId)
        assertEquals(PullRequestStatus.OPEN, reopened.status)
    }

    @Test
    fun `reopen rejects MERGED PR`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.MERGED)
        assertFailsWith<IllegalArgumentException> { service.reopen(prId) }
    }

    @Test
    fun `markReady transitions DRAFT to OPEN`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.DRAFT)
        coEvery { prRepository.updateStatus(prId, PullRequestStatus.OPEN) } returns testPr(status = PullRequestStatus.OPEN)

        val ready = service.markReady(prId)
        assertEquals(PullRequestStatus.OPEN, ready.status)
    }

    @Test
    fun `markReady rejects non-DRAFT PR`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.OPEN)
        assertFailsWith<IllegalArgumentException> { service.markReady(prId) }
    }

    @Test
    fun `merge rejects non-OPEN PR`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.CLOSED)
        assertFailsWith<IllegalArgumentException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email")
        }
    }

    @Test
    fun `submitReview creates review record`() = runTest {
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { reviewRepository.create(any()) } answers { firstArg<Review>().copy(id = UUID.random()) }

        val reviewerId = UUID.random()
        val input = SubmitReviewInput(pullRequestId = prId, status = ReviewStatus.APPROVED, body = "LGTM")
        val review = service.submitReview(input, reviewerId)

        assertEquals(ReviewStatus.APPROVED, review.status)
        assertEquals("LGTM", review.body)
        coVerify { reviewRepository.create(match { it.pullRequestId == prId && it.reviewerId == reviewerId }) }
    }

    @Test
    fun `submitReview rejects review on non-OPEN PR`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.MERGED)
        assertFailsWith<IllegalArgumentException> {
            service.submitReview(SubmitReviewInput(prId, ReviewStatus.APPROVED), UUID.random())
        }
    }

    @Test
    fun `addReviewComment creates comment anchored to diff line`() = runTest {
        val reviewId = UUID.random()
        val commitSha = "a".repeat(40)
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { reviewCommentRepository.create(any()) } answers {
            firstArg<bosca.git.model.ReviewComment>().copy(id = UUID.random())
        }

        val comment = service.addReviewComment(
            reviewId = reviewId,
            pullRequestId = prId,
            authorId = authorId,
            filePath = "src/Main.kt",
            oldLineNumber = null,
            newLineNumber = 42,
            commitSha = commitSha,
            content = "Consider renaming this"
        )
        assertEquals("src/Main.kt", comment.filePath)
        assertEquals(42, comment.newLineNumber)
        assertEquals(commitSha, comment.commitSha)
        coVerify {
            pubSub.publish(
                "bosca.git.pull_request",
                any<kotlinx.serialization.SerializationStrategy<PullRequestEvent>>(),
                match<PullRequestEvent> {
                    it.action == PullRequestEventAction.COMMENTED &&
                        it.actorId == authorId &&
                        it.actorName == "Git collaborator" &&
                        it.body == "Consider renaming this" &&
                        it.filePath == "src/Main.kt" &&
                        it.lineNumber == 42
                },
            )
        }
    }

    @Test
    fun `addReviewComment uses the old line when the new line is absent`() = runTest {
        val reviewId = UUID.random()
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { reviewCommentRepository.create(any()) } answers {
            firstArg<bosca.git.model.ReviewComment>().copy(id = UUID.random())
        }

        service.addReviewComment(
            reviewId = reviewId,
            pullRequestId = prId,
            authorId = authorId,
            filePath = "src/Old.kt",
            oldLineNumber = 17,
            newLineNumber = null,
            commitSha = "b".repeat(40),
            content = "This line was removed",
        )

        coVerify {
            pubSub.publish(
                "bosca.git.pull_request",
                any<kotlinx.serialization.SerializationStrategy<PullRequestEvent>>(),
                match<PullRequestEvent> {
                    it.action == PullRequestEventAction.COMMENTED && it.lineNumber == 17
                },
            )
        }
    }

    @Test
    fun `comment and thread resolution reject a missing pull request`() = runTest {
        coEvery { prRepository.findById(prId) } returns null

        assertFailsWith<NoSuchElementException> {
            service.addReviewComment(
                reviewId = UUID.random(),
                pullRequestId = prId,
                authorId = authorId,
                filePath = "src/Main.kt",
                oldLineNumber = null,
                newLineNumber = 1,
                commitSha = "c".repeat(40),
                content = "Comment",
            )
        }
        assertFailsWith<NoSuchElementException> {
            service.resolveThread(prId, "src/Main.kt", 1)
        }
        coVerify(exactly = 0) { reviewCommentRepository.create(any()) }
        coVerify(exactly = 0) { reviewCommentRepository.resolveThread(any(), any(), any()) }
    }

    @Test
    fun `resolveThread delegates to repository`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr()
        service.resolveThread(prId, "src/Main.kt", 42)
        coVerify { reviewCommentRepository.resolveThread(prId, "src/Main.kt", 42) }
    }

    @Test
    fun `onSourceBranchPushed marks comments outdated`() = runTest {
        val pr = testPr()
        val newSha = "c".repeat(40)
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null

        service.onSourceBranchPushed(prId, newSha)
        coVerify { reviewCommentRepository.markOutdatedByPullRequest(prId, newSha) }
    }

    @Test
    fun `onSourceBranchPushed dismisses reviews when dismissStaleReviews enabled`() = runTest {
        val pr = testPr()
        val newSha = "c".repeat(40)
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            dismissStaleReviews = true
        )
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule

        service.onSourceBranchPushed(prId, newSha)
        coVerify { reviewRepository.dismissByPullRequest(prId, "source_updated") }
    }

    @Test
    fun `onSourceBranchPushed does not dismiss when dismissStaleReviews disabled`() = runTest {
        val pr = testPr()
        val newSha = "c".repeat(40)
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            dismissStaleReviews = false
        )
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule

        service.onSourceBranchPushed(prId, newSha)
        coVerify(exactly = 0) { reviewRepository.dismissByPullRequest(any(), any()) }
    }

    @Test
    fun `create cross-fork PR sets sourceRepositoryId`() = runTest {
        val sourceRepoId = UUID.random()
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 1
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }

        val input = CreatePullRequestInput(
            repositoryId = repositoryId,
            title = "Cross-fork feature",
            sourceBranch = "feature",
            targetBranch = "main",
            sourceRepositoryId = sourceRepoId
        )
        val pr = service.create(input, authorId)
        assertEquals(sourceRepoId, pr.sourceRepositoryId)
        assertEquals(repositoryId, pr.repositoryId)
    }

    @Test
    fun `merge blocked by insufficient approvals`() = runTest {
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 2)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        coEvery { reviewRepository.findApprovedByPullRequest(prId) } returns listOf(
            Review(pullRequestId = prId, reviewerId = UUID.random(), status = ReviewStatus.APPROVED)
        )

        assertFailsWith<IllegalArgumentException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email")
        }
    }

    @Test
    fun `addAssignee delegates to repository`() = runTest {
        val assigneeId = UUID.random()
        coEvery { prRepository.findById(prId) } returns testPr()

        service.addAssignee(prId, assigneeId)
        coVerify { assigneeRepository.add(prId, assigneeId) }
    }

    @Test
    fun `addAssignee throws when PR not found`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> {
            service.addAssignee(prId, UUID.random())
        }
    }

    @Test
    fun `removeAssignee delegates to repository`() = runTest {
        val assigneeId = UUID.random()
        coEvery { prRepository.findById(prId) } returns testPr()

        service.removeAssignee(prId, assigneeId)
        coVerify { assigneeRepository.remove(prId, assigneeId) }
    }

    @Test
    fun `removeAssignee throws when PR not found`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> {
            service.removeAssignee(prId, UUID.random())
        }
    }

    @Test
    fun `getAssignees returns profile IDs from repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        coEvery { assigneeRepository.findByPullRequest(prId) } returns ids

        val result = service.getAssignees(prId)
        assertEquals(ids, result)
    }

    // ── appended coverage: lookups, dispatch arms, real merge, mergeability ──

    @Test
    fun `update throws when the pull request is missing`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> {
            service.update(prId, UpdatePullRequestInput(title = "t"))
        }
    }

    @Test
    fun `findById and findByNumber delegate to the repository`() = runTest {
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { prRepository.findByNumber(repositoryId, 7) } returns pr
        assertEquals(pr, service.findById(prId))
        assertEquals(pr, service.findByNumber(repositoryId, 7))
    }

    @Test
    fun `findOpenBySourceBranch delegates to the repository`() = runTest {
        val pullRequests = listOf(testPr())
        coEvery { prRepository.findOpenBySourceBranch(repositoryId, "feature/nested") } returns pullRequests

        assertEquals(pullRequests, service.findOpenBySourceBranch(repositoryId, "feature/nested"))
    }

    @Test
    fun `dependency lookups delegate to the dependency repository`() = runTest {
        val dependency = testPr(number = 2).copy(id = UUID.random())
        val dependent = testPr(number = 3).copy(id = UUID.random())
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)
        coEvery { dependencyRepository.findDependents(prId) } returns listOf(dependent)

        assertEquals(listOf(dependency), service.getDependencies(prId))
        assertEquals(listOf(dependent), service.getDependents(prId))
    }

    @Test
    fun `addDependency persists a cross-repository dependency`() = runTest {
        val dependencyId = UUID.random()
        val dependency = testPr(number = 2).copy(id = dependencyId, repositoryId = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.findById(dependencyId) } returns dependency
        coEvery { dependencyRepository.findDependencies(any()) } returns emptyList()

        val result = service.addDependency(prId, dependencyId)

        assertEquals(prId, result.id)
        coVerify { dependencyRepository.add(prId, dependencyId) }
    }

    @Test
    fun `addDependency fails when the graph lock is busy or the dependency is missing`() = runTest {
        val dependencyId = UUID.random()
        coEvery { lock.acquire(any(), any(), any()) } returns false
        assertFailsWith<IllegalStateException> { service.addDependency(prId, dependencyId) }

        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.findById(dependencyId) } returns null
        assertFailsWith<NoSuchElementException> { service.addDependency(prId, dependencyId) }
    }

    @Test
    fun `addDependency rejects self links and duplicate links`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.addDependency(prId, prId) }

        val dependencyId = UUID.random()
        val dependency = testPr(number = 2).copy(id = dependencyId)
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.findById(dependencyId) } returns dependency
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)

        assertFailsWith<IllegalArgumentException> { service.addDependency(prId, dependencyId) }
        coVerify(exactly = 0) { dependencyRepository.add(any(), any()) }
    }

    @Test
    fun `addDependency rejects a transitive cycle`() = runTest {
        val dependencyId = UUID.random()
        val intermediateId = UUID.random()
        val dependency = testPr(number = 2).copy(id = dependencyId)
        val intermediate = testPr(number = 3).copy(id = intermediateId)
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.findById(dependencyId) } returns dependency
        coEvery { dependencyRepository.findDependencies(prId) } returns emptyList()
        coEvery { dependencyRepository.findDependencies(dependencyId) } returns listOf(intermediate)
        coEvery { dependencyRepository.findDependencies(intermediateId) } returns listOf(testPr())

        assertFailsWith<IllegalArgumentException> { service.addDependency(prId, dependencyId) }
        coVerify(exactly = 0) { dependencyRepository.add(any(), any()) }
    }

    @Test
    fun `removeDependency rejects missing links and removes existing links`() = runTest {
        val dependencyId = UUID.random()
        val dependency = testPr(number = 2).copy(id = dependencyId)
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns emptyList()
        assertFailsWith<IllegalArgumentException> { service.removeDependency(prId, dependencyId) }

        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)
        assertEquals(prId, service.removeDependency(prId, dependencyId).id)
        coVerify { dependencyRepository.remove(prId, dependencyId) }
    }

    @Test
    fun `dependency changes reject missing and completed parent pull requests`() = runTest {
        val dependencyId = UUID.random()
        coEvery { prRepository.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> { service.removeDependency(prId, dependencyId) }

        coEvery { prRepository.findById(prId) } returns testPr(PullRequestStatus.MERGED)
        assertFailsWith<IllegalArgumentException> { service.removeDependency(prId, dependencyId) }
    }

    @Test
    fun `getMergePlan orders a diamond graph dependencies first and skips merged nodes`() = runTest {
        val left = testPr(number = 2).copy(id = UUID.random())
        val right = testPr(number = 3).copy(id = UUID.random())
        val shared = testPr(number = 4).copy(id = UUID.random())
        val alreadyMerged = testPr(PullRequestStatus.MERGED, 5).copy(id = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(left, right, alreadyMerged)
        coEvery { dependencyRepository.findDependencies(left.id) } returns listOf(shared)
        coEvery { dependencyRepository.findDependencies(right.id) } returns listOf(shared)
        coEvery { dependencyRepository.findDependencies(shared.id) } returns emptyList()

        assertEquals(listOf(shared.id, left.id, right.id, prId), service.getMergePlan(prId).map { it.id })
        coVerify(exactly = 0) { dependencyRepository.findDependencies(alreadyMerged.id) }
    }

    @Test
    fun `getMergePlan rejects missing pull requests and malformed stored cycles`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        assertFailsWith<NoSuchElementException> { service.getMergePlan(prId) }

        val dependency = testPr(number = 2).copy(id = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)
        coEvery { dependencyRepository.findDependencies(dependency.id) } returns listOf(testPr())
        assertFailsWith<IllegalStateException> { service.getMergePlan(prId) }
    }

    @Test
    fun `merge rejects an unresolved dependency before touching repository data`() = runTest {
        val dependency = testPr(number = 2).copy(id = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)

        assertFailsWith<IllegalArgumentException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email")
        }
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `mergeWithDependencies preflight rejects a draft dependency before merging anything`() = runTest {
        val dependency = testPr(PullRequestStatus.DRAFT, 2).copy(id = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)
        coEvery { dependencyRepository.findDependencies(dependency.id) } returns emptyList()

        assertFailsWith<IllegalArgumentException> {
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email",
                listOf(dependency.id, prId),
            )
        }
        coVerify(exactly = 0) { prRepository.updateMergeState(any()) }
    }

    @Test
    fun `mergeWithDependencies rejects a graph changed after authorization`() = runTest {
        val dependency = testPr(number = 2).copy(id = UUID.random())
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { dependencyRepository.findDependencies(prId) } returns listOf(dependency)
        coEvery { dependencyRepository.findDependencies(dependency.id) } returns emptyList()

        val failure = assertFailsWith<IllegalArgumentException> {
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email",
                listOf(prId),
            )
        }

        assertEquals("Pull request dependencies changed; review the merge plan and try again", failure.message)
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `mergeWithDependencies rejects an already merged root and an unmergeable root`() = runTest {
        coEvery { prRepository.findById(prId) } returns testPr(PullRequestStatus.MERGED)
        assertFailsWith<IllegalArgumentException> {
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email", emptyList(),
            )
        }

        val repository = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repository
        coEvery { prRepository.findById(prId) } returns testPr().copy(sourceBranch = "missing")
        coEvery { dependencyRepository.findDependencies(prId) } returns emptyList()
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null
        val failure = assertFailsWith<IllegalArgumentException> {
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email", listOf(prId),
            )
        }
        assertEquals("Pull request #1 cannot be merged", failure.message)
        repository.close()
    }

    @Test
    fun `mergeWithDependencies tolerates a pull request merged after preflight`() = runTest {
        val open = testPr()
        val merged = open.copy(status = PullRequestStatus.MERGED)
        coEvery { prRepository.findById(prId) } returnsMany listOf(open, open, merged)
        coEvery { dependencyRepository.findDependencies(prId) } returns emptyList()
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null
        val repository = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repository

        assertEquals(
            emptyList(),
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email", listOf(prId),
            ),
        )
        repository.close()
    }

    @Test
    fun `mergeWithDependencies fails if a planned pull request disappears after preflight`() = runTest {
        val open = testPr()
        coEvery { prRepository.findById(prId) } returnsMany listOf(open, open, null)
        coEvery { dependencyRepository.findDependencies(prId) } returns emptyList()
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null
        val repository = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repository

        assertFailsWith<NoSuchElementException> {
            service.mergeWithDependencies(
                prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email", listOf(prId),
            )
        }
        repository.close()
    }

    @Test
    fun `mergeWithDependencies merges nested repositories dependency first`() = runTest {
        val dependencyId = UUID.random()
        val dependencyRepositoryId = UUID.random()
        var dependency = testPr(number = 2).copy(
            id = dependencyId,
            repositoryId = dependencyRepositoryId,
            title = "Git dependency",
        )
        var parent = testPr().copy(title = "Workspace parent")
        val dependencyGitRepository = seedMergeableRepo()
        val parentGitRepository = seedMergeableRepo()
        io.mockk.every { dfsManager.open(dependencyRepositoryId) } answers {
            dependencyGitRepository.incrementOpen()
            dependencyGitRepository
        }
        io.mockk.every { dfsManager.open(repositoryId) } answers {
            parentGitRepository.incrementOpen()
            parentGitRepository
        }
        coEvery { prRepository.findById(dependencyId) } answers { dependency }
        coEvery { prRepository.findById(prId) } answers { parent }
        coEvery { dependencyRepository.findDependencies(dependencyId) } returns emptyList()
        coEvery { dependencyRepository.findDependencies(prId) } answers { listOf(dependency) }
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            requiredApprovals = 0,
        )
        coEvery { reviewRepository.findLatestChangesRequested(any()) } returns null
        coEvery { prRepository.updateMergeState(any()) } answers {
            val updated = firstArg<PullRequest>()
            if (updated.id == dependencyId) dependency = updated else parent = updated
            updated
        }

        val merged = service.mergeWithDependencies(
            prId, MergeStrategy.MERGE_COMMIT, authorId, "name", "email",
            listOf(dependencyId, prId),
        )

        assertEquals(listOf(dependencyId, prId), merged.map { it.id })
        assertEquals(PullRequestStatus.MERGED, dependency.status)
        assertEquals(PullRequestStatus.MERGED, parent.status)
        dependencyGitRepository.close()
        parentGitRepository.close()
    }

    @Test
    fun `activity dispatch tolerates profiles that cannot be resolved`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 1
        coEvery { repoRepository.findById(repositoryId) } returns null
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }
        coEvery { profileService.getAllByIds(any()) } throws IllegalStateException("profile unavailable")

        service.create(
            CreatePullRequestInput(
                repositoryId = repositoryId,
                title = "Notification fallback",
                sourceBranch = "feature/fallback",
                targetBranch = "main",
            ),
            authorId,
        )

        coVerify {
            pubSub.publish(
                "bosca.git.pull_request",
                any<kotlinx.serialization.SerializationStrategy<PullRequestEvent>>(),
                match<PullRequestEvent> {
                    it.repositoryName == "Repository" &&
                        it.authorId == authorId &&
                        it.actorId == null &&
                        it.recipientIds.isEmpty()
                },
            )
        }
    }

    @Test
    fun `activity dispatch preserves coroutine cancellation during profile resolution`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 1
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId) }
        coEvery { profileService.getAllByIds(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            service.create(
                CreatePullRequestInput(
                    repositoryId = repositoryId,
                    title = "Cancelled notification",
                    sourceBranch = "feature/cancelled",
                    targetBranch = "main",
                ),
                authorId,
            )
        }
    }

    @Test
    fun `findByRepository dispatches on author, status, and default arms`() = runTest {
        val author = UUID.random()
        service.findByRepository(repositoryId, null, author, 0, 10)
        coVerify { prRepository.findByAuthor(repositoryId, author, 0, 10) }

        service.findByRepository(repositoryId, PullRequestStatus.OPEN, null, 0, 10)
        coVerify { prRepository.findByRepositoryAndStatus(repositoryId, PullRequestStatus.OPEN, 0, 10) }

        service.findByRepository(repositoryId, null, null, 5, 20)
        coVerify { prRepository.findByRepository(repositoryId, 5, 20) }
    }

    @Test
    fun `findByRepositories passes every filter and skips an empty repository list`() = runTest {
        val repositoryIds = listOf(repositoryId, UUID.random())
        val author = UUID.random()

        service.findByRepositories(repositoryIds, PullRequestStatus.OPEN, author, 1, 10)
        coVerify {
            prRepository.findByRepositories(
                PullRequestRepositoryQuery(repositoryIds, PullRequestStatus.OPEN, author, 1, 10)
            )
        }

        assertEquals(emptyList(), service.findByRepositories(emptyList(), null, null, 0, 25))
        coVerify(exactly = 0) { prRepository.findByRepositories(match { it.repositoryIds.isEmpty() }) }
    }

    private fun seedMergeableRepo(): org.eclipse.jgit.internal.storage.dfs.InMemoryRepository {
        val repo = org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(
            org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("pr-merge")
        )
        val ins = repo.objectDatabase.newInserter()
        val author = org.eclipse.jgit.lib.PersonIdent("T", "t@x")

        val baseBlob = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = org.eclipse.jgit.lib.TreeFormatter().also { it.append("f.txt", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, baseBlob) }
        val baseCommit = ins.insert(org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(baseTree)); setAuthor(author); setCommitter(author); setMessage("base")
        })

        val featBlob = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "feature\n".toByteArray())
        val featTree = org.eclipse.jgit.lib.TreeFormatter().also { it.append("f.txt", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, featBlob) }
        val featCommit = ins.insert(org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(featTree)); setParentId(baseCommit); setAuthor(author); setCommitter(author); setMessage("feat")
        })
        ins.flush()

        repo.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(baseCommit); update() }
        repo.refDatabase.newUpdate("refs/heads/feature", true).apply { setNewObjectId(featCommit); update() }
        return repo
    }

    @Test
    fun `merge performs a real merge, advances the target ref, and records merge state`() = runTest {
        val repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null
        coEvery { prRepository.updateMergeState(any()) } answers { firstArg() }

        val merged = service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "Merger", "m@x")

        assertEquals(PullRequestStatus.MERGED, merged.status)
        assertEquals(MergeStrategy.MERGE_COMMIT, merged.mergeStrategy)
        val mergeSha = merged.mergeSha
        kotlin.test.assertNotNull(mergeSha)
        assertEquals(mergeSha, repo.refDatabase.findRef("refs/heads/main")?.objectId?.name())
        repo.close()
    }

    @Test
    fun `merge fails as busy when the write lock is held`() = runTest {
        val repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null
        coEvery { lock.acquire(any(), any(), any()) } returns false

        // A GC or push holds the repository write lock: the merge must not
        // write anything and the caller gets a clearly retryable error.
        assertFailsWith<RepositoryWriteBusyException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "Merger", "m@x")
        }

        coVerify(exactly = 0) { prRepository.updateMergeState(any()) }
        repo.close()
    }

    @Test
    fun `merge fails when a branch is missing`() = runTest {
        val repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns null
        coEvery { prRepository.findById(prId) } returns testPr().copy(sourceBranch = "missing")
        assertFailsWith<IllegalStateException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e")
        }
        repo.close()
    }

    @Test
    fun `checkMergeability reports success for mergeable branches and failure for missing refs`() = runTest {
        val repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { prRepository.findById(prId) } returns testPr()
        assertTrue(service.checkMergeability(prId).success)

        coEvery { prRepository.findById(prId) } returns testPr().copy(sourceBranch = "missing")
        io.mockk.every { dfsManager.open(repositoryId) } returns seedMergeableRepo()
        assertEquals(false, service.checkMergeability(prId).success)

        coEvery { prRepository.findById(prId) } returns testPr().copy(targetBranch = "missing")
        io.mockk.every { dfsManager.open(repositoryId) } returns seedMergeableRepo()
        assertEquals(false, service.checkMergeability(prId).success)
        repo.close()
    }

    @Test
    fun `create extracts task keys from the title and branch`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 9
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId, number = 9) }

        service.create(
            CreatePullRequestInput(
                repositoryId = repositoryId, title = "GIT-123 fix things",
                sourceBranch = "feature/GIT-456-stuff", targetBranch = "main",
            ),
            authorId,
        )

        coVerify { taskPrRefRepository.create(match { it.taskKey == "GIT-123" }) }
        coVerify { taskPrRefRepository.create(match { it.taskKey == "GIT-456" }) }
    }

    @Test
    fun `create tolerates task reference persistence failures`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 9
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId, number = 9) }
        coEvery { taskPrRefRepository.create(any()) } throws RuntimeException("dup")

        val pr = service.create(
            CreatePullRequestInput(repositoryId = repositoryId, title = "GIT-1 x", sourceBranch = "f", targetBranch = "main"),
            authorId,
        )
        assertEquals(9, pr.number)
    }

    @Test
    fun `getReviews and comments delegate to their repositories`() = runTest {
        service.getReviews(prId)
        coVerify { reviewRepository.findByPullRequest(prId) }
        service.getCommentsForPullRequest(prId)
        coVerify { reviewCommentRepository.findByPullRequest(prId) }
        coEvery { prRepository.findById(prId) } returns testPr()
        service.resolveThread(prId, "f.txt", 3)
        coVerify { reviewCommentRepository.resolveThread(prId, "f.txt", 3) }
    }

    @Test
    fun `onSourceBranchPushed ignores missing or non-open pull requests`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        service.onSourceBranchPushed(prId, "a".repeat(40))
        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.MERGED)
        service.onSourceBranchPushed(prId, "a".repeat(40))
        coVerify(exactly = 0) { reviewCommentRepository.markOutdatedByPullRequest(any(), any()) }
    }

    // ── appended coverage: merge protection enforcement arms ────────────

    private fun protectedMergeSetup(rule: BranchProtectionRule) {
        val repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        coEvery { prRepository.updateMergeState(any()) } answers { firstArg() }
    }

    private fun approval(reviewerId: UUID, created: bosca.serialization.OffsetDateTime) = Review(
        id = UUID.random(), pullRequestId = prId, reviewerId = reviewerId,
        status = ReviewStatus.APPROVED, created = created,
    )

    @Test
    fun `merge is blocked while requested changes are unresolved`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0))
        val reviewer = UUID.random()
        val now = bosca.serialization.OffsetDateTime.now()
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns Review(
            id = UUID.random(), pullRequestId = prId, reviewerId = reviewer,
            status = ReviewStatus.CHANGES_REQUESTED, created = now,
        )
        coEvery { reviewRepository.findApprovedByPullRequest(prId) } returns
            listOf(approval(reviewer, now.minusHours(1))) // approval PRECEDES the request

        assertFailsWith<IllegalArgumentException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e")
        }
    }

    @Test
    fun `merge proceeds when the requesting reviewer later approves`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0))
        val reviewer = UUID.random()
        val now = bosca.serialization.OffsetDateTime.now()
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns Review(
            id = UUID.random(), pullRequestId = prId, reviewerId = reviewer,
            status = ReviewStatus.CHANGES_REQUESTED, created = now.minusHours(2),
        )
        coEvery { reviewRepository.findApprovedByPullRequest(prId) } returns listOf(approval(reviewer, now))

        val merged = service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e")
        assertEquals(PullRequestStatus.MERGED, merged.status)
    }

    @Test
    fun `merge is blocked when required status checks are failing`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0, requireStatusChecks = listOf("ci")))
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns null
        coEvery { commitStatusService.areRequiredChecksPassing(repositoryId, any(), listOf("ci")) } returns false

        assertFailsWith<IllegalArgumentException> {
            service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e")
        }
    }

    @Test
    fun `merge proceeds when required status checks pass`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0, requireStatusChecks = listOf("ci")))
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns null
        coEvery { commitStatusService.areRequiredChecksPassing(repositoryId, any(), listOf("ci")) } returns true

        val merged = service.merge(prId, MergeStrategy.SQUASH, authorId, "n", "e")
        assertEquals(MergeStrategy.SQUASH, merged.mergeStrategy)
    }

    @Test
    fun `merge with sufficient approvals passes the approval gate`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 1))
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns null
        coEvery { reviewRepository.findApprovedByPullRequest(prId) } returns
            listOf(approval(UUID.random(), bosca.serialization.OffsetDateTime.now()))

        val merged = service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e")
        assertEquals(PullRequestStatus.MERGED, merged.status)
    }

    @Test
    fun `every pr-scoped operation throws when the pull request is missing`() = runTest {
        coEvery { prRepository.findById(prId) } returns null
        val ops: List<suspend () -> Any?> = listOf(
            { service.update(prId, UpdatePullRequestInput(title = "t")) },
            { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e") },
            { service.checkMergeability(prId) },
            { service.close(prId) },
            { service.reopen(prId) },
            { service.markReady(prId) },
            { service.submitReview(SubmitReviewInput(pullRequestId = prId, status = ReviewStatus.APPROVED, body = null), authorId) },
        )
        for ((i, op) in ops.withIndex()) {
            try { op(); kotlin.test.fail("op #$i should throw") }
            catch (_: NoSuchElementException) { /* expected */ }
        }
    }

    @Test
    fun `status persistence failures surface as IllegalStateException`() = runTest {
        coEvery { prRepository.updateStatus(prId, any()) } returns null

        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.OPEN)
        try { service.close(prId); kotlin.test.fail() } catch (_: IllegalStateException) {}

        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.CLOSED)
        try { service.reopen(prId); kotlin.test.fail() } catch (_: IllegalStateException) {}

        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.DRAFT)
        try { service.markReady(prId); kotlin.test.fail() } catch (_: IllegalStateException) {}

        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.update(any()) } returns null
        try { service.update(prId, UpdatePullRequestInput(title = null, description = null)); kotlin.test.fail() }
        catch (_: IllegalStateException) {}
    }

    @Test
    fun `merge surfaces a missing target branch and a failed state update`() = runTest {
        // Missing target branch.
        var repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns null
        coEvery { prRepository.findById(prId) } returns testPr().copy(targetBranch = "missing")
        try { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e"); kotlin.test.fail() }
        catch (_: IllegalStateException) {}
        repo.close()

        // Merge succeeds in git but the state row update fails.
        repo = seedMergeableRepo()
        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { prRepository.updateMergeState(any()) } returns null
        try { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e"); kotlin.test.fail() }
        catch (_: IllegalStateException) {}
        repo.close()
    }

    @Test
    fun `update can change only the description and close accepts drafts`() = runTest {
        val pr = testPr()
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { prRepository.update(any()) } answers { firstArg() }
        val updated = service.update(prId, UpdatePullRequestInput(title = null, description = "new desc"))
        assertEquals("Test PR", updated.title)
        assertEquals("new desc", updated.description)

        coEvery { prRepository.findById(prId) } returns testPr(status = PullRequestStatus.DRAFT)
        coEvery { prRepository.updateStatus(prId, PullRequestStatus.CLOSED) } returns testPr(status = PullRequestStatus.CLOSED)
        assertEquals(PullRequestStatus.CLOSED, service.close(prId).status)
    }

    @Test
    fun `merge propagates git-level conflicts as failures`() = runTest {
        val repo = seedMergeableRepo()
        // Diverge main so the merge genuinely conflicts.
        val ins = repo.objectDatabase.newInserter()
        val person = org.eclipse.jgit.lib.PersonIdent("T", "t@x")
        val blob = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "conflict\n".toByteArray())
        val tree = org.eclipse.jgit.lib.TreeFormatter().apply {
            append("f.txt", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob)
        }
        val baseId = repo.refDatabase.findRef("refs/heads/main")!!.objectId
        val commit = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(ins.insert(tree)); setAuthor(person); setCommitter(person)
            setMessage("diverge"); setParentId(baseId)
        }
        val newMain = ins.insert(commit)
        ins.flush()
        repo.refDatabase.newUpdate("refs/heads/main", false).apply {
            setNewObjectId(newMain); isForceUpdate = true; update()
        }

        io.mockk.every { dfsManager.open(repositoryId) } returns repo
        coEvery { prRepository.findById(prId) } returns testPr()
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns null

        try { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e"); kotlin.test.fail() }
        catch (_: IllegalStateException) { /* conflict surfaces as failed merge */ }
        repo.close()
    }

    @Test
    fun `create records task keys from a description too`() = runTest {
        coEvery { repoRepository.incrementPrNumber(repositoryId) } returns 3
        coEvery { prRepository.create(any()) } answers { firstArg<PullRequest>().copy(id = prId, number = 3) }
        service.create(
            CreatePullRequestInput(
                repositoryId = repositoryId, title = "no key here",
                description = "fixes GIT-321", sourceBranch = "f", targetBranch = "main",
            ),
            authorId,
        )
        coVerify { taskPrRefRepository.create(match { it.taskKey == "GIT-321" }) }
    }

    @Test
    fun `an approval from a different reviewer does not resolve requested changes`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0))
        val requester = UUID.random()
        val now = bosca.serialization.OffsetDateTime.now()
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns Review(
            id = UUID.random(), pullRequestId = prId, reviewerId = requester,
            status = ReviewStatus.CHANGES_REQUESTED, created = now.minusHours(2),
        )
        coEvery { reviewRepository.findApprovedByPullRequest(prId) } returns
            listOf(approval(UUID.random(), now)) // someone ELSE approved

        try { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e"); kotlin.test.fail() }
        catch (_: IllegalArgumentException) { /* still blocked */ }
    }

    @Test
    fun `status checks fail loudly when the source branch is missing`() = runTest {
        protectedMergeSetup(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requiredApprovals = 0, requireStatusChecks = listOf("ci")))
        coEvery { reviewRepository.findLatestChangesRequested(prId) } returns null
        coEvery { prRepository.findById(prId) } returns testPr().copy(sourceBranch = "gone")

        try { service.merge(prId, MergeStrategy.MERGE_COMMIT, authorId, "n", "e"); kotlin.test.fail() }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
