@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.transport

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.Visibility
import bosca.git.service.BranchProtectionService
import bosca.git.service.RepositoryContentValidationError
import bosca.git.service.RepositoryContentValidator
import bosca.git.service.RepositoryContentValidatorRegistry
import bosca.git.service.RepositoryService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [GitPreReceiveHook]'s content-validation stage: validators are looked
 * up by the repository's content type, receive only files under their path
 * prefixes, and reject the command on validation errors or validator/tree
 * failures — never crash the push.
 */
class GitPreReceiveHookContentValidationTest {

    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val registry = RepositoryContentValidatorRegistry()
    private val hook = GitPreReceiveHook(branchProtectionService, repositoryService, registry)

    private val repositoryId = UUID.random()
    private lateinit var gitRepo: InMemoryRepository
    private lateinit var commitId: ObjectId

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        gitRepo = InMemoryRepository(DfsRepositoryDescription("prehook"))
        commitId = seedCommit()
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns null
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        ProviderRegistry.clear()
    }

    private fun seedCommit(): ObjectId {
        val ins = gitRepo.objectDatabase.newInserter()
        val tree = TreeFormatter()
        tree.append("readme.md", FileMode.REGULAR_FILE, ins.insert(Constants.OBJ_BLOB, "hi".toByteArray()))
        val sub = TreeFormatter()
        sub.append("source.kts", FileMode.REGULAR_FILE, ins.insert(Constants.OBJ_BLOB, "fun x() {}".toByteArray()))
        tree.append("scripts", FileMode.TREE, ins.insert(sub))
        val treeId = ins.insert(tree)
        val author = PersonIdent("T", "t@x")
        val id = ins.insert(CommitBuilder().apply { setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("c") })
        ins.flush()
        return id
    }

    /** A ReceivePack whose repository resolves ids/trees from the real in-memory repo. */
    private fun receivePack(): ReceivePack {
        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        every { boscaRepo.objectDatabase } returns gitRepo.objectDatabase
        every { boscaRepo.newObjectReader() } answers { gitRepo.newObjectReader() }
        return mockk<ReceivePack>(relaxed = true).also {
            every { it.repository } returns boscaRepo
        }
    }

    private fun repoOf(contentType: RepositoryContentType?) = Repository(
        id = repositoryId, slug = "r", name = "R", ownerId = UUID.random(),
        visibility = Visibility.PRIVATE, contentType = contentType,
    )

    private fun validator(
        prefixes: List<String> = emptyList(),
        result: (Map<String, String>) -> List<RepositoryContentValidationError> = { emptyList() },
    ): RepositoryContentValidator {
        return object : RepositoryContentValidator {
            override val contentType = RepositoryContentType.SCRIPT_PROJECT
            override val pathPrefixes = prefixes
            var seenFiles: Map<String, String>? = null
            override suspend fun validate(repositoryId: UUID, files: Map<String, String>): List<RepositoryContentValidationError> {
                seenFiles = files
                return result(files)
            }
        }
    }

    private fun command(newId: ObjectId = commitId) =
        ReceiveCommand(ObjectId.zeroId(), newId, "refs/heads/main")

    @Test
    fun `passes when the validator reports no errors`() {
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        registry.register(validator())
        val cmd = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.result)
    }

    @Test
    fun `rejects when the validator reports errors`() {
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        registry.register(validator { listOf(RepositoryContentValidationError("scripts/source.kts", "bad syntax")) })
        val cmd = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.result)
        assertTrue(cmd.message.contains("bad syntax"))
    }

    @Test
    fun `rejects when the validator itself throws`() {
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        registry.register(validator { throw IllegalStateException("validator crashed") })
        val cmd = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.result)
        assertTrue(cmd.message.contains("Content validation failed"))
    }

    @Test
    fun `rejects when the proposed tree cannot be read`() {
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        registry.register(validator())
        val cmd = command(newId = ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, cmd.result)
        assertTrue(cmd.message.contains("Failed to read proposed tree"))
    }

    @Test
    fun `path prefixes limit the files a validator sees`() {
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        var seen: Map<String, String>? = null
        registry.register(validator(prefixes = listOf("scripts/")) { files -> seen = files; emptyList() })
        hook.onPreReceive(receivePack(), mutableListOf(command()))
        assertEquals(setOf("scripts/source.kts"), seen?.keys)
    }

    @Test
    fun `skips validation for deletes, untyped repos, and unregistered types`() {
        // Delete command: newId is zero.
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        registry.register(validator { listOf(RepositoryContentValidationError("x", "should not run")) })
        val del = ReceiveCommand(commitId, ObjectId.zeroId(), "refs/heads/main")
        hook.onPreReceive(receivePack(), mutableListOf(del))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, del.result)

        // No content type on the repository.
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(null)
        val cmd = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.result)

        // Content type without a registered validator.
        coEvery { repositoryService.findById(repositoryId) } returns repoOf(RepositoryContentType.DOCUMENTATION)
        val cmd2 = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd2))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd2.result)
    }

    @Test
    fun `skips validation when the repository row is missing`() {
        coEvery { repositoryService.findById(repositoryId) } returns null
        registry.register(validator { listOf(RepositoryContentValidationError("x", "no")) })
        val cmd = command()
        hook.onPreReceive(receivePack(), mutableListOf(cmd))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, cmd.result)
    }
}
