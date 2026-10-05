package bosca.git.transport

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.requestCache
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.git.service.RefUpdateNotifier
import bosca.git.service.RefUpdateNotifierImpl
import bosca.git.service.WebhookService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import kotlin.test.BeforeTest
import kotlin.test.Test

class GitPostReceiveHookTest {

    private val repositoryRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val packRepository = mockk<DfsPackRepository>(relaxed = true)
    private val webhookService = mockk<WebhookService>(relaxed = true)
    private val taskCommitRefRepository = mockk<TaskCommitReferenceRepository>(relaxed = true)
    private val notifier = RefUpdateNotifierImpl(repositoryRepository, packRepository, webhookService, taskCommitRefRepository)
    private val hook = GitPostReceiveHook(notifier)
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
    }

    @Test
    fun `updates disk size after successful push`() {
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        coEvery { packRepository.sumPackSizeBytes(repositoryId) } returns 42L

        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("a".repeat(40)),
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify { repositoryRepository.updateDiskSize(repositoryId, 42L) }
    }

    @Test
    fun `forwards initiating principal to ref update notifier`() {
        val principalId = UUID.random()
        val attributedNotifier = mockk<RefUpdateNotifier>(relaxed = true)
        val attributedHook = GitPostReceiveHook(attributedNotifier)
            .withInitiatingPrincipal(principalId)
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("d".repeat(40)),
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        attributedHook.onPostReceive(rp, mutableListOf(command))

        coVerify {
            attributedNotifier.notifyRefsUpdated(
                repository = boscaRepo,
                repositoryId = repositoryId,
                updates = match {
                    it.single().refName == "refs/heads/main" &&
                        it.single().oldId == ObjectId.zeroId() &&
                        it.single().newId == ObjectId.fromString("d".repeat(40))
                },
                pusherPrincipalId = principalId,
            )
        }
    }

    @Test
    fun `establishes request cache and connection contexts for notifier`() {
        val contextRequiringNotifier = mockk<RefUpdateNotifier>()
        coEvery {
            contextRequiringNotifier.notifyRefsUpdated(any(), any(), any(), any())
        } coAnswers {
            requestCache()
            connection()
        }
        val contextRequiringHook = GitPostReceiveHook(contextRequiringNotifier)
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("e".repeat(40)),
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        contextRequiringHook.onPostReceive(rp, mutableListOf(command))

        coVerify(exactly = 1) {
            contextRequiringNotifier.notifyRefsUpdated(any(), repositoryId, any(), null)
        }
    }

    @Test
    fun `skips disk size update when no successful commands`() {
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId

        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "test rejection")

        hook.onPostReceive(rp, mutableListOf(command))

        coVerify(exactly = 0) { repositoryRepository.updateDiskSize(any(), any()) }
    }

    @Test
    fun `handles non-BoscaDfsRepository gracefully`() {
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns mockk(relaxed = true)

        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("c".repeat(40)),
            "refs/heads/main"
        )
        command.setResult(ReceiveCommand.Result.OK)

        hook.onPostReceive(rp, mutableListOf(command))
    }
}
