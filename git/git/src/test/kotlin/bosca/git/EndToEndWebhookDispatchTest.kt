@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.model.WebhookEvent
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.git.service.RefUpdateNotifierImpl
import bosca.git.service.WebhookService
import bosca.git.transport.GitPostReceiveHook
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests that the [GitPostReceiveHook] correctly dispatches webhook events
 * and extracts task keys from commit messages after a successful push.
 * Verifies event type classification (PUSH, BRANCH_CREATED, BRANCH_DELETED,
 * TAG_CREATED, TAG_DELETED) and payload correctness.
 */
class EndToEndWebhookDispatchTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val repositoryRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val packRepository = mockk<bosca.git.repository.DfsPackRepository>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val taskCommitRefRepository = mockk<TaskCommitReferenceRepository>(relaxed = true)
    private val author = PersonIdent("Pusher", "push@bosca.io")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun openRepo(): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@EndToEndWebhookDispatchTest.repositoryId
            this.storageAdapter = this@EndToEndWebhookDispatchTest.storageAdapter
            this.refAdapter = this@EndToEndWebhookDispatchTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    @Test
    fun `push to existing branch dispatches PUSH event`() {
        val repo = openRepo()
        val commitId = createCommit(repo, "initial push\n")
        val refUpdate = repo.refDatabase.newUpdate("refs/heads/main", true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val oldSha = "a".repeat(40)
        val command = ReceiveCommand(
            ObjectId.fromString(oldSha),
            commitId,
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        val payloadSlot = slot<String>()
        coVerify {
            webhookService.dispatch(repositoryId, WebhookEvent.PUSH, capture(payloadSlot))
        }
        val payload = payloadSlot.captured
        assertTrue(payload.contains("\"ref\":\"refs/heads/main\""), "Payload must contain ref name")
        assertTrue(payload.contains(commitId.name()), "Payload must contain new commit SHA")
        assertTrue(payload.contains(oldSha), "Payload must contain old SHA")
    }

    @Test
    fun `creating new branch dispatches BRANCH_CREATED event`() {
        val repo = openRepo()
        val commitId = createCommit(repo, "new branch content\n")

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            commitId,
            "refs/heads/feature-x"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_CREATED, any())
        }
    }

    @Test
    fun `deleting branch dispatches BRANCH_DELETED event`() {
        val repo = openRepo()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.zeroId(),
            "refs/heads/old-branch"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_DELETED, any())
        }
    }

    @Test
    fun `creating tag dispatches TAG_CREATED event`() {
        val repo = openRepo()
        val commitId = createCommit(repo, "tagged content\n")

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            commitId,
            "refs/tags/v1.0"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            webhookService.dispatch(repositoryId, WebhookEvent.TAG_CREATED, any())
        }
    }

    @Test
    fun `deleting tag dispatches TAG_DELETED event`() {
        val repo = openRepo()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.fromString("c".repeat(40)),
            ObjectId.zeroId(),
            "refs/tags/v0.9"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            webhookService.dispatch(repositoryId, WebhookEvent.TAG_DELETED, any())
        }
    }

    @Test
    fun `failed commands do not trigger any webhook events`() {
        val repo = openRepo()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("d".repeat(40)),
            "refs/heads/rejected-branch"
        )
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "branch protection")

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify(exactly = 0) {
            webhookService.dispatch(any(), any(), any())
        }
    }

    @Test
    fun `multiple commands dispatch correct events for each`() {
        val repo = openRepo()
        val commit1 = createCommit(repo, "push content\n")
        val commit2 = createCommit(repo, "tag content\n")

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val pushCommand = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            commit1,
            "refs/heads/main"
        )
        pushCommand.setResult(ReceiveCommand.Result.OK)

        val branchCreateCommand = ReceiveCommand(
            ObjectId.zeroId(),
            commit1,
            "refs/heads/new-branch"
        )
        branchCreateCommand.setResult(ReceiveCommand.Result.OK)

        val tagCommand = ReceiveCommand(
            ObjectId.zeroId(),
            commit2,
            "refs/tags/v1.0"
        )
        tagCommand.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(pushCommand, branchCreateCommand, tagCommand))

        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.PUSH, any()) }
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_CREATED, any()) }
        coVerify { webhookService.dispatch(repositoryId, WebhookEvent.TAG_CREATED, any()) }
    }

    @Test
    fun `push with task key in commit message extracts task reference`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, "fix content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("fix.txt", FileMode.REGULAR_FILE, blob)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndWebhookDispatchTest.author
            this.committer = this@EndToEndWebhookDispatchTest.author
            message = "Fix PROJ-123: resolve login issue"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val refUpdate = repo.refDatabase.newUpdate("refs/heads/main", true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            commitId,
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            taskCommitRefRepository.create(match {
                it.taskKey == "PROJ-123" && it.commitSha == commitId.name()
            })
        }
    }

    @Test
    fun `disk size is updated after successful push`() {
        val repo = openRepo()
        val commitId = createCommit(repo, "content for size check\n")
        val refUpdate = repo.refDatabase.newUpdate("refs/heads/main", true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()

        val hook = GitPostReceiveHook(RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository))
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns repo

        val command = ReceiveCommand(ObjectId.zeroId(), commitId, "refs/heads/main")
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify { repositoryRepository.updateDiskSize(repositoryId, any()) }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createCommit(repo: BoscaDfsRepository, content: String): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, content.toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blob)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndWebhookDispatchTest.author
            this.committer = this@EndToEndWebhookDispatchTest.author
            message = "test commit"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
    }
}
