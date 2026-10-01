package bosca.git.transport

import bosca.db.ConnectionPool
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.model.BranchProtectionRule
import bosca.git.service.BranchProtectionService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitPreReceiveHookTest {

    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
    private val hook = GitPreReceiveHook(branchProtectionService)
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    }

    private fun receivePack(): ReceivePack {
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        return mockk<ReceivePack>(relaxed = true).also {
            every { it.repository } returns boscaRepo
        }
    }

    @Test
    fun `unprotected branch allows all commands`() {
        coEvery { branchProtectionService.findMatchingRule(repositoryId, any()) } returns null
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("a".repeat(40)),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `protected branch rejects direct push when requirePullRequest is true`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
    }

    @Test
    fun `main protection does not reject a new branch`() {
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "kjb/test") } returns null
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/kjb/test"
        )

        hook.onPreReceive(receivePack(), mutableListOf(command))

        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `branch protection lookup failure becomes a Git command rejection`() {
        coEvery {
            branchProtectionService.findMatchingRule(repositoryId, "kjb/test")
        } throws IllegalStateException("restrictPushAccess is required")
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/kjb/test"
        )

        hook.onPreReceive(receivePack(), mutableListOf(command))

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
        assertEquals(GitPreReceiveHook.VALIDATION_UNAVAILABLE_MESSAGE, command.message)
    }

    @Test
    fun `non-fatal linkage failure becomes a Git command rejection`() {
        coEvery {
            branchProtectionService.findMatchingRule(repositoryId, "kjb/test")
        } throws NoClassDefFoundError("native glob dependency")
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/kjb/test"
        )

        hook.onPreReceive(receivePack(), mutableListOf(command))

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
        assertEquals(GitPreReceiveHook.VALIDATION_UNAVAILABLE_MESSAGE, command.message)
    }

    @Test
    fun `cancellation still escapes push validation`() {
        coEvery {
            branchProtectionService.findMatchingRule(repositoryId, "kjb/test")
        } throws kotlinx.coroutines.CancellationException("cancelled")
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/kjb/test"
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            hook.onPreReceive(receivePack(), mutableListOf(command))
        }
    }

    @Test
    fun `fatal error still escapes push validation`() {
        coEvery {
            branchProtectionService.findMatchingRule(repositoryId, "kjb/test")
        } throws AssertionError("fatal invariant")
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/kjb/test"
        )

        assertFailsWith<AssertionError> {
            hook.onPreReceive(receivePack(), mutableListOf(command))
        }
    }

    @Test
    fun `protected branch rejects force push when allowForcePush is false`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", allowForcePush = false)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main",
            ReceiveCommand.Type.UPDATE_NONFASTFORWARD
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
    }

    @Test
    fun `protected branch rejects deletion when allowDeletion is false`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", allowDeletion = false)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.zeroId(),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
    }

    @Test
    fun `protected branch allows deletion when allowDeletion is true`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", allowDeletion = true)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.zeroId(),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `non-branch refs are not subject to protection`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, any()) } returns rule
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("c".repeat(40)),
            "refs/tags/v1.0"
        )
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `restrictPushAccess allows listed profile`() {
        val allowedId = UUID.random()
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            restrictPushAccess = listOf(allowedId)
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val pushHook = hook.forPusher(allowedId)
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        pushHook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `restrictPushAccess rejects unlisted profile`() {
        val allowedId = UUID.random()
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            restrictPushAccess = listOf(allowedId)
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val pushHook = hook.forPusher(UUID.random())
        val rp = receivePack()
        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        pushHook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
    }

    @Test
    fun `concurrent pushes through one shared hook are each checked against their own pusher`() {
        val allowedId = UUID.random()
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            restrictPushAccess = listOf(allowedId)
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        fun command() = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )

        // Both pushes are bound before either runs, as two overlapping pushes would be.
        val allowedPush = hook.forPusher(allowedId)
        val unlistedPush = hook.forPusher(UUID.random())
        val unlistedCommand = command()
        unlistedPush.onPreReceive(receivePack(), mutableListOf(unlistedCommand))
        val allowedCommand = command()
        allowedPush.onPreReceive(receivePack(), mutableListOf(allowedCommand))

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, unlistedCommand.result)
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, allowedCommand.result)
        assertEquals(null, hook.pusherId, "Binding a pusher must not change the shared hook")
    }

    @Test
    fun `non-DFS repositories are ignored`() {
        val rp = io.mockk.mockk<ReceivePack>(relaxed = true)
        io.mockk.every { rp.repository } returns
            org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(
                org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("plain"))
        val command = ReceiveCommand(ObjectId.zeroId(), ObjectId.fromString("a".repeat(40)), "refs/heads/main")
        hook.onPreReceive(rp, mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }

    @Test
    fun `commands already rejected by earlier stages are skipped`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val command = ReceiveCommand(ObjectId.fromString("a".repeat(40)), ObjectId.fromString("b".repeat(40)), "refs/heads/main")
        command.setResult(ReceiveCommand.Result.REJECTED_NONFASTFORWARD, "no ff")
        hook.onPreReceive(receivePack(), mutableListOf(command))
        assertEquals(ReceiveCommand.Result.REJECTED_NONFASTFORWARD, command.result)
    }

    @Test
    fun `merge commits from pull requests bypass the direct-push block`() {
        val rule = BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true)
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule
        val command = ReceiveCommand(ObjectId.fromString("a".repeat(40)), ObjectId.fromString("b".repeat(40)), "refs/heads/main")
        command.setResult(ReceiveCommand.Result.NOT_ATTEMPTED, "Merge pull request #7 from feature")
        hook.onPreReceive(receivePack(), mutableListOf(command))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.result)
    }
}
