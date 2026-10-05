package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that committing through the API/UI write path (the GraphQL
 * `commitFile`/`deleteFile` mutations) fans out through [RefUpdateNotifier]
 * exactly like a native push, so editing a pipeline YAML in the web editor
 * re-syncs pipelines and dispatches the same downstream events.
 */
class RepositoryWriteServiceImplTest {

    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val notifier = mockk<RefUpdateNotifier>()
    private val lockFactory = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>(relaxed = true)
    private val repositoryId = UUID.random()
    private lateinit var inMemoryRepo: InMemoryRepository
    private lateinit var service: RepositoryWriteServiceImpl

    @BeforeTest
    fun setup() {
        inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("test"))
        every { dfsManager.open(repositoryId) } returns inMemoryRepo
        coEvery { notifier.notifyRefsUpdated(any(), any(), any(), any()) } just Runs
        coEvery { lockFactory.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        service = RepositoryWriteServiceImpl(dfsManager, notifier, lockFactory)
    }

    private fun seedMain(path: String, content: String): ObjectId {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, content.toByteArray())
        val tree = TreeFormatter()
        tree.append(path, FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder()
        commit.setTreeId(treeId)
        commit.author = PersonIdent("Seed", "seed@bosca.io")
        commit.committer = PersonIdent("Seed", "seed@bosca.io")
        commit.message = "seed"
        val commitId = inserter.insert(commit)
        inserter.flush()
        val refUpdate = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", false)
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()
        return commitId
    }

    @Test
    fun `commitFile on a new branch retains the initiating principal`() = runTest {
        val updates = slot<List<RefChange>>()
        val initiatingPrincipalId = UUID.random()
        val pusher = slot<UUID>()
        coEvery {
            notifier.notifyRefsUpdated(any(), repositoryId, capture(updates), capture(pusher))
        } just Runs

        val result = service.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = "main",
                path = ".bosca/pipelines/ci.yaml",
                content = "name: CI\n",
                message = "add pipeline",
                authorName = "Author",
                authorEmail = "author@bosca.io",
            ),
            initiatingPrincipalId,
        )

        coVerify(exactly = 1) { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) }
        assertEquals(initiatingPrincipalId, pusher.captured)
        assertEquals(1, updates.captured.size)
        val change = updates.captured.first()
        assertEquals("refs/heads/main", change.refName)
        assertEquals(ObjectId.zeroId(), change.oldId)
        assertEquals(result.commitSha, change.newId.name())
    }

    @Test
    fun `commitFile on an existing branch notifies with the parent as old ref`() = runTest {
        val parent = seedMain("README.md", "# seed\n")
        val updates = slot<List<RefChange>>()
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, capture(updates), any()) } just Runs

        val result = service.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = "main",
                path = ".bosca/pipelines/ci.yaml",
                content = "name: CI changed\n",
                message = "edit pipeline",
                authorName = "Author",
                authorEmail = "author@bosca.io",
            )
        )

        val change = updates.captured.single()
        assertEquals(parent, change.oldId)
        assertEquals(result.commitSha, change.newId.name())
    }

    @Test
    fun `deleteFile notifies so a removed pipeline is re-synced`() = runTest {
        val parent = seedMain("pipeline.yaml", "name: CI\n")
        val updates = slot<List<RefChange>>()
        val initiatingPrincipalId = UUID.random()
        val pusher = slot<UUID>()
        coEvery {
            notifier.notifyRefsUpdated(any(), repositoryId, capture(updates), capture(pusher))
        } just Runs

        service.deleteFile(
            DeleteFileInput(
                repositoryId = repositoryId,
                branch = "main",
                path = "pipeline.yaml",
                message = "remove pipeline",
                authorName = "Author",
                authorEmail = "author@bosca.io",
            ),
            initiatingPrincipalId,
        )

        coVerify(exactly = 1) { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) }
        assertEquals(initiatingPrincipalId, pusher.captured)
        val change = updates.captured.single()
        assertEquals("refs/heads/main", change.refName)
        assertEquals(parent, change.oldId)
    }

    @Test
    fun `commitFiles retains the initiating principal for its single ref update`() = runTest {
        val initiatingPrincipalId = UUID.random()
        val pusher = slot<UUID>()
        val updates = slot<List<RefChange>>()
        coEvery {
            notifier.notifyRefsUpdated(any(), repositoryId, capture(updates), capture(pusher))
        } just Runs

        service.commitFiles(
            CommitFilesInput(
                repositoryId = repositoryId,
                files = mapOf("a.txt" to "a", "b.txt" to "b"),
                message = "add files",
                authorName = "Author",
                authorEmail = "author@bosca.io",
            ),
            initiatingPrincipalId,
        )

        assertEquals(initiatingPrincipalId, pusher.captured)
        assertEquals(1, updates.captured.size)
        assertEquals("refs/heads/main", updates.captured.single().refName)
        assertEquals("a", service.readFile(repositoryId, "refs/heads/main", "a.txt"))
        assertEquals("b", service.readFile(repositoryId, "refs/heads/main", "b.txt"))
    }

    // ── appended coverage: replace path, delete guards, readFile ────────

    @Test
    fun `commitFile replaces the content of an existing file`() = runTest {
        seedMain("a.txt", "v1")

        service.commitFile(
            CommitFileInput(
                repositoryId = repositoryId, branch = "main", path = "a.txt",
                content = "v2", message = "update", authorName = "A", authorEmail = "a@x",
            )
        )

        assertEquals("v2", service.readFile(repositoryId, "refs/heads/main", "a.txt"))
    }

    @Test
    fun `deleteFile throws for an unknown branch`() = runTest {
        seedMain("a.txt", "v1")
        try {
            service.deleteFile(
                DeleteFileInput(
                    repositoryId = repositoryId, branch = "nope", path = "a.txt",
                    message = "d", authorName = "A", authorEmail = "a@x",
                )
            )
            kotlin.test.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `readFile returns null for unknown refs and paths`() = runTest {
        seedMain("a.txt", "v1")
        assertEquals("v1", service.readFile(repositoryId, "refs/heads/main", "a.txt"))
        assertEquals(null, service.readFile(repositoryId, "refs/heads/nope", "a.txt"))
        assertEquals(null, service.readFile(repositoryId, "refs/heads/main", "missing.txt"))
    }

    @Test
    fun `deleteFile removes only the target and keeps sibling files`() = runTest {
        seedMain("keep.txt", "stay")
        // Add a second file via commitFile so the tree has two entries.
        service.commitFile(
            CommitFileInput(
                repositoryId = repositoryId, branch = "main", path = "gone.txt",
                content = "bye", message = "add", authorName = "A", authorEmail = "a@x",
            )
        )

        service.deleteFile(
            DeleteFileInput(
                repositoryId = repositoryId, branch = "main", path = "gone.txt",
                message = "rm", authorName = "A", authorEmail = "a@x",
            )
        )

        assertEquals("stay", service.readFile(repositoryId, "refs/heads/main", "keep.txt"))
        assertEquals(null, service.readFile(repositoryId, "refs/heads/main", "gone.txt"))
    }

    @Test
    fun `commitFile and deleteFile fail as busy when the write lock is held`() = runTest {
        seedMain("a.txt", "v1")
        coEvery { lock.acquire(any(), any(), any()) } returns false

        // A GC or push holds the repository write lock: nothing may be written
        // and the caller gets a clearly retryable error.
        assertFailsWith<RepositoryWriteBusyException> {
            service.commitFile(
                CommitFileInput(
                    repositoryId = repositoryId, branch = "main", path = "a.txt",
                    content = "v2", message = "m", authorName = "A", authorEmail = "a@x",
                )
            )
        }
        assertFailsWith<RepositoryWriteBusyException> {
            service.deleteFile(
                DeleteFileInput(
                    repositoryId = repositoryId, branch = "main", path = "a.txt",
                    message = "rm", authorName = "A", authorEmail = "a@x",
                )
            )
        }

        assertEquals("v1", service.readFile(repositoryId, "refs/heads/main", "a.txt"))
        coVerify(exactly = 0) { notifier.notifyRefsUpdated(any(), any(), any(), any()) }
    }

    // ─── createTag: initiator propagation + idempotency ─────────────

    private fun tagInput(pusher: UUID? = null, allowExisting: Boolean = true) = CreateTagInput(
        repositoryId = repositoryId,
        tag = "v6.2.0",
        targetRef = "refs/heads/main",
        message = "Release v6.2.0",
        taggerName = "Bosca Release",
        taggerEmail = "release@bosca",
        pusherPrincipalId = pusher,
        allowExisting = allowExisting,
    )

    @Test
    fun `createTag without allowExisting throws for an existing tag even at the same commit`() = runTest {
        seedMain("a.txt", "v1")
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) } just Runs
        service.createTag(tagInput())

        assertFailsWith<IllegalArgumentException> { service.createTag(tagInput(allowExisting = false)) }
        coVerify(exactly = 1) { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) }
    }

    @Test
    fun `createTag notifies with the initiating principal as the pusher`() = runTest {
        val commitId = seedMain("a.txt", "v1")
        val initiator = UUID.random()
        val pusher = slot<UUID>()
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, any(), capture(pusher)) } just Runs

        val result = service.createTag(tagInput(pusher = initiator))

        assertTrue(result.created)
        assertEquals(commitId.name(), result.commitSha)
        assertEquals(initiator, pusher.captured)
    }

    @Test
    fun `createTag over the same commit is a no-op that does not re-notify`() = runTest {
        seedMain("a.txt", "v1")
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) } just Runs

        val first = service.createTag(tagInput())
        val second = service.createTag(tagInput())

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.tagSha, second.tagSha)
        assertEquals(first.commitSha, second.commitSha)
        coVerify(exactly = 1) { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) }
    }

    @Test
    fun `createTag over a different commit fails naming both commits`() = runTest {
        val firstCommit = seedMain("a.txt", "v1")
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) } just Runs
        service.createTag(tagInput())

        // Advance main through the real commit path (a re-seeded parentless commit would be
        // rejected as a non-fast-forward and leave the ref unmoved).
        val advanced = service.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = "main",
                path = "a.txt",
                content = "v2",
                message = "advance",
                authorName = "Author",
                authorEmail = "author@bosca.io",
            )
        )
        val e = assertFailsWith<IllegalStateException> { service.createTag(tagInput()) }
        assertTrue(firstCommit.name() in (e.message ?: ""), e.message)
        assertTrue(advanced.commitSha in (e.message ?: ""), e.message)
    }
}
