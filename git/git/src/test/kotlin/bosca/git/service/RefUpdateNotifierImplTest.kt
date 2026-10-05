@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.GitRefKind
import bosca.git.model.GitRefUpdateAction
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.RefUpdateEvent
import bosca.git.model.Repository
import bosca.git.model.WebhookEvent
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.pubsub.PubSubService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Covers [RefUpdateNotifierImpl] — the single funnel for push and UI-write
 * notifications: disk-size refresh, webhook event mapping (push/create/delete
 * for branches and tags), task-key extraction from new commits, and the three
 * job enqueues — with every side effect individually failure-tolerant.
 */
class RefUpdateNotifierImplTest {

    private val repositoryRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val packRepository = mockk<DfsPackRepository>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val taskCommitRefRepository = mockk<TaskCommitReferenceRepository>(relaxed = true)
    private val repoIndexEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val fileIndexEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val pipelineEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val pullRequestService = mockk<PullRequestService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val pubSub = mockk<PubSubService>(relaxed = true)

    private val notifier = RefUpdateNotifierImpl(
        repositoryRepository,
        packRepository,
        webhookService,
        taskCommitRefRepository,
        pullRequestService,
        profileService,
        securityService,
    )

    private val repositoryId = UUID.random()
    private val ownerId = UUID.random()
    private lateinit var gitRepo: InMemoryRepository
    private lateinit var firstCommit: ObjectId
    private lateinit var secondCommit: ObjectId

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        provides<JobConfigurationEnqueuer>(name = "repository-index", singleton = true) { repoIndexEnqueuer }
        provides<JobConfigurationEnqueuer>(name = "file-content-index", singleton = true) { fileIndexEnqueuer }
        provides<JobConfigurationEnqueuer>(name = "pipeline-trigger", singleton = true) { pipelineEnqueuer }
        provides<PubSubService>(singleton = true) { pubSub }
        gitRepo = InMemoryRepository(DfsRepositoryDescription("notifier"))
        seedCommits()
        coEvery { packRepository.sumPackSizeBytes(repositoryId) } returns 1234L
        coEvery { repositoryRepository.findById(repositoryId) } returns Repository(
            id = repositoryId,
            slug = "bosca",
            name = "Bosca",
            ownerId = ownerId,
        )
        coEvery { profileService.getAllByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Profile(
                    id = id,
                    type = ProfileType.GENERIC,
                    name = "Git actor",
                    visibility = ProfileVisibility.USER,
                )
            }
        }
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        ProviderRegistry.clear()
    }

    private fun seedCommits() {
        val ins = gitRepo.objectDatabase.newInserter()
        val author = PersonIdent("T", "t@x")
        val blob = ins.insert(Constants.OBJ_BLOB, "a".toByteArray())
        val treeId = ins.insert(TreeFormatter().apply { append("f.txt", FileMode.REGULAR_FILE, blob) })
        firstCommit = ins.insert(CommitBuilder().apply {
            setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("base")
        })
        secondCommit = ins.insert(CommitBuilder().apply {
            setTreeId(treeId); setParentId(firstCommit); setAuthor(author); setCommitter(author)
            setMessage("GIT-77 add feature")
        })
        ins.flush()
    }

    private fun change(ref: String, old: ObjectId, new: ObjectId) = RefChange(ref, old, new)

    @Test
    fun `empty updates are a no-op`() = runTest {
        notifier.notifyRefsUpdated(gitRepo, repositoryId, emptyList(), null)
        coVerify(exactly = 0) { repositoryRepository.updateDiskSize(any(), any()) }
    }

    @Test
    fun `branch push refreshes disk size, dispatches PUSH, stores task keys, and enqueues jobs`() = runTest {
        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(change("refs/heads/main", firstCommit, secondCommit)),
            UUID.random(),
        )

        coVerify { repositoryRepository.updateDiskSize(repositoryId, 1234L) }
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.PUSH, any()) }
        coVerify { taskCommitRefRepository.create(match { it.taskKey == "GIT-77" }) }
        coVerify { repoIndexEnqueuer.enqueue(any(), any()) }
        coVerify { fileIndexEnqueuer.enqueue(any(), any()) }
        coVerify { pipelineEnqueuer.enqueue(any(), any()) }
    }

    @Test
    fun `nested source branch emits ref activity and updates matching open pull requests`() = runTest {
        val pullRequestId = UUID.random()
        val pusherId = UUID.random()
        val actorProfileId = UUID.random()
        val principal = mockk<Principal>()
        coEvery { profileService.getAllByIds(listOf(pusherId)) } returns emptyList()
        coEvery { securityService.getPrincipalById(pusherId) } returns principal
        coEvery { profileService.getPrimaryProfile(principal) } returns Profile(
            id = actorProfileId,
            type = ProfileType.GENERIC,
            name = "Primary actor",
            visibility = ProfileVisibility.USER,
        )
        coEvery { pullRequestService.findOpenBySourceBranch(repositoryId, "feature/GIT-77") } returns listOf(
            PullRequest(
                id = pullRequestId,
                repositoryId = repositoryId,
                number = 7,
                title = "Notify collaborators",
                authorId = UUID.random(),
                sourceBranch = "feature/GIT-77",
                targetBranch = "main",
                status = PullRequestStatus.OPEN,
            )
        )

        notifier.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/heads/feature/GIT-77", firstCommit, secondCommit)),
            pusherId,
        )

        coVerify { pullRequestService.onSourceBranchPushed(pullRequestId, secondCommit.name()) }
        coVerify {
            pubSub.publish(
                "bosca.git.ref_update",
                any<kotlinx.serialization.SerializationStrategy<RefUpdateEvent>>(),
                match<RefUpdateEvent> {
                    it.repositoryName == "Bosca" &&
                        it.refName == "feature/GIT-77" &&
                        it.kind == GitRefKind.BRANCH &&
                        it.action == GitRefUpdateAction.UPDATED &&
                        it.actorId == actorProfileId &&
                        it.recipientIds == setOf(ownerId) &&
                        it.taskKeys == setOf("GIT-77") &&
                        it.commitMessages == listOf("GIT-77 add feature")
                },
            )
        }
    }

    @Test
    fun `webhook failure does not suppress pull request updates or ref activity`() = runTest {
        val pullRequestId = UUID.random()
        coEvery { webhookService.dispatch(repositoryId, WebhookEvent.PUSH, any()) } throws
            IllegalStateException("webhooks unavailable")
        coEvery { pullRequestService.findOpenBySourceBranch(repositoryId, "feature/GIT-77") } returns listOf(
            PullRequest(
                id = pullRequestId,
                repositoryId = repositoryId,
                number = 8,
                title = "Keep state current",
                authorId = UUID.random(),
                sourceBranch = "feature/GIT-77",
                targetBranch = "main",
                status = PullRequestStatus.OPEN,
            )
        )

        notifier.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/heads/feature/GIT-77", firstCommit, secondCommit)),
            null,
        )

        coVerify { pullRequestService.onSourceBranchPushed(pullRequestId, secondCommit.name()) }
        coVerify {
            pubSub.publish(
                "bosca.git.ref_update",
                any<kotlinx.serialization.SerializationStrategy<RefUpdateEvent>>(),
                match<RefUpdateEvent> { it.refName == "feature/GIT-77" },
            )
        }
    }

    @Test
    fun `pull request and task reference failures do not suppress ref activity`() = runTest {
        val pullRequestId = UUID.random()
        val pusherId = UUID.random()
        coEvery { profileService.getAllByIds(listOf(pusherId)) } throws
            IllegalStateException("profiles unavailable")
        coEvery { taskCommitRefRepository.create(any()) } throws
            IllegalStateException("task references unavailable")
        coEvery { pullRequestService.findOpenBySourceBranch(repositoryId, "feature/GIT-77") } returns listOf(
            PullRequest(
                id = pullRequestId,
                repositoryId = repositoryId,
                number = 9,
                title = "Keep notifying",
                authorId = UUID.random(),
                sourceBranch = "feature/GIT-77",
                targetBranch = "main",
                status = PullRequestStatus.OPEN,
            ),
        )
        coEvery { pullRequestService.onSourceBranchPushed(pullRequestId, any()) } throws
            IllegalStateException("pull request update unavailable")

        notifier.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/heads/feature/GIT-77", firstCommit, secondCommit)),
            pusherId,
        )

        coVerify {
            pubSub.publish(
                "bosca.git.ref_update",
                any<kotlinx.serialization.SerializationStrategy<RefUpdateEvent>>(),
                match<RefUpdateEvent> { it.refName == "feature/GIT-77" && it.actorId == null },
            )
        }
    }

    @Test
    fun `annotated tag creation peels commit details and emits ref activity`() = runTest {
        val tagId = gitRepo.newObjectInserter().use { inserter ->
            val tag = TagBuilder().apply {
                tag = "v1.0.0"
                setObjectId(secondCommit, Constants.OBJ_COMMIT)
                tagger = PersonIdent("T", "t@x")
                message = "Release v1.0.0"
            }
            inserter.insert(tag).also { inserter.flush() }
        }

        notifier.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/tags/v1.0.0", ObjectId.zeroId(), tagId)),
            null,
        )

        coVerify {
            pubSub.publish(
                "bosca.git.ref_update",
                any<kotlinx.serialization.SerializationStrategy<RefUpdateEvent>>(),
                match<RefUpdateEvent> {
                    it.refName == "v1.0.0" &&
                        it.kind == GitRefKind.TAG &&
                        it.action == GitRefUpdateAction.CREATED &&
                        it.afterSha == tagId.name() &&
                        "GIT-77 add feature" in it.commitMessages
                },
            )
        }
    }

    @Test
    fun `missing repository metadata skips the notification event`() = runTest {
        coEvery { repositoryRepository.findById(repositoryId) } returns null

        notifier.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/heads/main", firstCommit, secondCommit)),
            null,
        )

        coVerify(exactly = 0) {
            pubSub.publish(
                "bosca.git.ref_update",
                any<kotlinx.serialization.SerializationStrategy<RefUpdateEvent>>(),
                any<RefUpdateEvent>(),
            )
        }
    }

    @Test
    fun `notification works without pull request integration`() = runTest {
        val notifierWithoutPullRequests = RefUpdateNotifierImpl(
            repositoryRepository,
            packRepository,
            webhookService,
            taskCommitRefRepository,
        )

        notifierWithoutPullRequests.notifyRefsUpdated(
            gitRepo,
            repositoryId,
            listOf(change("refs/heads/main", firstCommit, secondCommit)),
            null,
        )

        coVerify(exactly = 0) { pullRequestService.findOpenBySourceBranch(any(), any()) }
    }

    @Test
    fun `branch create and delete map to their webhook events`() = runTest {
        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(change("refs/heads/new", ObjectId.zeroId(), secondCommit)),
            null,
        )
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_CREATED, any()) }

        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(change("refs/heads/old", firstCommit, ObjectId.zeroId())),
            null,
        )
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_DELETED, any()) }
        // A deletion has no new commits, so no pipeline trigger for it.
        coVerify(exactly = 1) { pipelineEnqueuer.enqueue(any(), any()) } // only the create
    }

    @Test
    fun `tag create and delete map to their webhook events`() = runTest {
        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(
                change("refs/tags/v1", ObjectId.zeroId(), secondCommit),
                change("refs/tags/v0", firstCommit, ObjectId.zeroId()),
            ),
            null,
        )
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.TAG_CREATED, any()) }
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.TAG_DELETED, any()) }
        // Tags don't feed the file-content index.
        coVerify(exactly = 0) { fileIndexEnqueuer.enqueue(any(), any()) }
    }

    @Test
    fun `refs outside heads and tags dispatch no webhooks`() = runTest {
        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(change("refs/notes/commits", firstCommit, secondCommit)),
            null,
        )
        coVerify(exactly = 0) { webhookService.dispatch(any(), any(), any()) }
    }

    @Test
    fun `failures in disk size, webhooks, and enqueues are tolerated`() = runTest {
        coEvery { packRepository.sumPackSizeBytes(repositoryId) } throws RuntimeException("db down")
        coEvery { webhookService.dispatch(any(), any(), any()) } throws RuntimeException("hook down")
        coEvery { repoIndexEnqueuer.enqueue(any(), any()) } throws RuntimeException("queue down")
        coEvery { pipelineEnqueuer.enqueue(any(), any()) } throws RuntimeException("queue down")

        // Must not throw despite every side effect failing.
        notifier.notifyRefsUpdated(
            gitRepo, repositoryId,
            listOf(change("refs/heads/main", firstCommit, secondCommit)),
            null,
        )
    }
}
