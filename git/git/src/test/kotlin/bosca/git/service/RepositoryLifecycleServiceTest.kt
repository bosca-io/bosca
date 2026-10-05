package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.dfs.DfsPack
import bosca.git.dfs.DfsPackExtension
import bosca.git.repository.GitRepositoryRepository
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.BundleWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryLifecycleServiceTest {

    private val repositoryRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val packRepository = mockk<DfsPackRepository>(relaxed = true)
    private val refRepository = mockk<DfsRefRepository>(relaxed = true)
    private val objectStorage = mockk<ObjectStorageService>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val lockFactory = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>(relaxed = true)
    private lateinit var service: RepositoryLifecycleService
    private lateinit var inMemoryRepo: InMemoryRepository
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("test"))
        createTestCommit()
        every { dfsManager.open(repositoryId) } returns inMemoryRepo
        coEvery { lockFactory.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        service = RepositoryLifecycleServiceImpl(repositoryRepository, packRepository, refRepository, objectStorage, dfsManager, lockFactory)
    }

    private fun createTestCommit() {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val content = inserter.insert(Constants.OBJ_BLOB, "test content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, content)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder()
        commit.setTreeId(treeId)
        commit.setAuthor(PersonIdent("Test", "test@example.com"))
        commit.setCommitter(PersonIdent("Test", "test@example.com"))
        commit.setMessage("initial")
        val commitId = inserter.insert(commit)
        inserter.flush()
        val ref = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(commitId)
        ref.update()
    }

    @Test
    fun `runGc completes and updates disk size`() = runTest {
        coEvery { packRepository.sumPackSizeBytes(repositoryId) } returns 4096L
        assertTrue(service.runGc(repositoryId))
        coVerify { repositoryRepository.updateDiskSize(repositoryId, 4096L) }
    }

    @Test
    fun `repair completes and updates disk size`() = runTest {
        coEvery { packRepository.sumPackSizeBytes(repositoryId) } returns 2048L
        assertTrue(service.repair(repositoryId))
        coVerify { repositoryRepository.updateDiskSize(repositoryId, 2048L) }
    }

    @Test
    fun `runGc reports the skip when write lock is held`() = runTest {
        coEvery { lock.acquire(any(), any(), any()) } returns false

        assertFalse(service.runGc(repositoryId))

        // GC body never ran: the repository was never opened for packing and no
        // disk-size update was written.
        coVerify(exactly = 0) { repositoryRepository.updateDiskSize(repositoryId, any()) }
    }

    @Test
    fun `reapDeletedPacks removes storage then row for reapable packs`() = runTest {
        val packId = UUID.random()
        val storagePath = "git/$repositoryId/packs/pack-old.pack"
        coEvery { packRepository.findReapable(any(), any()) } returns listOf(
            DfsPack(id = packId, repositoryId = repositoryId, packName = "pack-old", committed = true)
        )
        coEvery { packRepository.findExtensions(packId) } returns listOf(
            DfsPackExtension(packId = packId, extension = "pack", fileSize = 10L, storagePath = storagePath)
        )

        service.reapDeletedPacks()

        coVerify { objectStorage.delete(match { it.toString() == storagePath }) }
        coVerify { packRepository.delete(packId) }
    }

    @Test
    fun `reapDeletedPacks keeps the row when storage deletion fails`() = runTest {
        val packId = UUID.random()
        coEvery { packRepository.findReapable(any(), any()) } returns listOf(
            DfsPack(id = packId, repositoryId = repositoryId, packName = "pack-old", committed = true)
        )
        coEvery { packRepository.findExtensions(packId) } returns listOf(
            DfsPackExtension(packId = packId, extension = "pack", fileSize = 10L, storagePath = "path")
        )
        coEvery { objectStorage.delete(any()) } throws RuntimeException("storage unavailable")

        service.reapDeletedPacks()

        // Storage removal failed, so the metadata row must survive for the next run.
        coVerify(exactly = 0) { packRepository.delete(packId) }
    }

    @Test
    fun `reapDeletedPacks is a no-op when nothing is reapable`() = runTest {
        coEvery { packRepository.findReapable(any(), any()) } returns emptyList()

        service.reapDeletedPacks()

        coVerify(exactly = 0) { packRepository.delete(any()) }
    }

    @Test
    fun `reapDeletedPacks reaps and defers the remainder when the batch is full`() = runTest {
        val full = (1..RepositoryLifecycleServiceImpl.PACK_REAP_BATCH_SIZE).map {
            DfsPack(id = UUID.random(), repositoryId = repositoryId, packName = "p$it", committed = true)
        }
        coEvery { packRepository.findReapable(any(), any()) } returns full
        coEvery { packRepository.findExtensions(any()) } returns emptyList()

        service.reapDeletedPacks()

        // Every pack in the full batch is reaped; the "batch full, defer remainder" branch runs.
        coVerify(exactly = RepositoryLifecycleServiceImpl.PACK_REAP_BATCH_SIZE) { packRepository.delete(any()) }
    }

    @Test
    fun `repair reports the skip when write lock is held`() = runTest {
        coEvery { lock.acquire(any(), any(), any()) } returns false

        assertFalse(service.repair(repositoryId))

        coVerify(exactly = 0) { repositoryRepository.updateDiskSize(repositoryId, any()) }
    }

    @Test
    fun `reapDeletedPacks rethrows cancellation instead of grinding through the batch`() = runTest {
        val packId1 = UUID.random()
        val packId2 = UUID.random()
        coEvery { packRepository.findReapable(any(), any()) } returns listOf(
            DfsPack(id = packId1, repositoryId = repositoryId, packName = "p1", committed = true),
            DfsPack(id = packId2, repositoryId = repositoryId, packName = "p2", committed = true),
        )
        coEvery { packRepository.findExtensions(any()) } returns listOf(
            DfsPackExtension(packId = packId1, extension = "pack", fileSize = 10L, storagePath = "path")
        )
        coEvery { objectStorage.delete(any()) } throws CancellationException("shutting down")

        assertFailsWith<CancellationException> { service.reapDeletedPacks() }

        // Cancellation stops the loop at the first pack; nothing is logged as a
        // spurious per-pack failure and no further packs are touched.
        coVerify(exactly = 0) { packRepository.delete(any()) }
        coVerify(exactly = 1) { objectStorage.delete(any()) }
    }

    @Test
    fun `purgeExpiredRepositories rethrows cancellation instead of continuing`() = runTest {
        val repo1 = UUID.random()
        val repo2 = UUID.random()
        coEvery { repositoryRepository.findExpiredSoftDeletes() } returns listOf(
            Repository(id = repo1, slug = "r1", name = "R1", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true),
            Repository(id = repo2, slug = "r2", name = "R2", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true),
        )
        coEvery { packRepository.findAll(repo1) } throws CancellationException("shutting down")

        assertFailsWith<CancellationException> { service.purgeExpiredRepositories() }

        coVerify(exactly = 0) { repositoryRepository.hardDelete(any()) }
    }

    @Test
    fun `purgeExpiredRepositories deletes expired repos`() = runTest {
        val expiredRepo = Repository(
            id = repositoryId,
            slug = "old-repo",
            name = "Old",
            ownerId = UUID.random(),
            visibility = Visibility.PRIVATE,
            deleted = true
        )
        coEvery { repositoryRepository.findExpiredSoftDeletes() } returns listOf(expiredRepo)
        coEvery { packRepository.findAll(repositoryId) } returns emptyList()

        service.purgeExpiredRepositories()

        coVerify { repositoryRepository.hardDelete(repositoryId) }
    }

    @Test
    fun `backup creates bundle in object storage`() = runTest {
        coEvery { objectStorage.setInputStream(any(), any(), any()) } returns 1024L

        val path = service.backup(repositoryId)
        assert(path.contains("git-backups"))
        coVerify { objectStorage.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `import clones from local bare repo`() = runTest {
        val bareDir = File.createTempFile("git-test-", ".git")
        bareDir.delete()
        try {
            val bareRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bareDir).call().repository
            val inserter = bareRepo.objectDatabase.newInserter()
            val blob = inserter.insert(Constants.OBJ_BLOB, "imported content\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("file.txt", FileMode.REGULAR_FILE, blob)
            val treeId = inserter.insert(tree)
            val commit = CommitBuilder()
            commit.setTreeId(treeId)
            commit.setAuthor(PersonIdent("Test", "test@example.com"))
            commit.setCommitter(PersonIdent("Test", "test@example.com"))
            commit.setMessage("imported commit")
            val commitId = inserter.insert(commit)
            inserter.flush()
            val ref = bareRepo.refDatabase.newUpdate("refs/heads/main", true)
            ref.setNewObjectId(commitId)
            ref.update()
            bareRepo.close()

            val importedRepoId = UUID.random()
            val importedRepo = InMemoryRepository(DfsRepositoryDescription("imported"))
            every { dfsManager.open(importedRepoId) } returns importedRepo
            coEvery { repositoryRepository.create(any()) } answers {
                (firstArg() as Repository).copy(id = importedRepoId)
            }
            coEvery { repositoryRepository.updateDiskSize(any(), any()) } returns Unit

            val result = service.importRepository(
                cloneUrl = bareDir.toURI().toString(),
                ownerId = UUID.random(),
                slug = "imported",
                name = "Imported Repo"
            )
            assertEquals("imported", result.slug)
        } finally {
            bareDir.deleteRecursively()
        }
    }

    @Test
    fun `mirror fetch updates refs from upstream`() = runTest {
        val bareDir = File.createTempFile("git-mirror-", ".git")
        bareDir.delete()
        try {
            val bareRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bareDir).call().repository
            val inserter = bareRepo.objectDatabase.newInserter()
            val blob = inserter.insert(Constants.OBJ_BLOB, "mirror content\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("file.txt", FileMode.REGULAR_FILE, blob)
            val treeId = inserter.insert(tree)
            val commit = CommitBuilder()
            commit.setTreeId(treeId)
            commit.setAuthor(PersonIdent("Test", "test@example.com"))
            commit.setCommitter(PersonIdent("Test", "test@example.com"))
            commit.setMessage("mirror commit")
            val commitId = inserter.insert(commit)
            inserter.flush()
            val ref = bareRepo.refDatabase.newUpdate("refs/heads/main", true)
            ref.setNewObjectId(commitId)
            ref.update()
            bareRepo.close()

            service.mirrorFetch(repositoryId, bareDir.toURI().toString())
            coVerify { repositoryRepository.updateDiskSize(repositoryId, any()) }
        } finally {
            bareDir.deleteRecursively()
        }
    }

    @Test
    fun `purgeExpiredRepositories does nothing when none are expired`() = runTest {
        coEvery { repositoryRepository.findExpiredSoftDeletes() } returns emptyList()

        service.purgeExpiredRepositories()

        coVerify(exactly = 0) { repositoryRepository.hardDelete(any()) }
    }

    @Test
    fun `restore rebuilds a repository from a valid bundle`() = runTest {
        val bundleBytes = ByteArrayOutputStream().also { out ->
            val writer = BundleWriter(inMemoryRepo)
            for (ref in inMemoryRepo.refDatabase.refs) writer.include(ref)
            writer.writeBundle(NullProgressMonitor.INSTANCE, out)
        }.toByteArray()

        val restoredId = UUID.random()
        val target = InMemoryRepository(DfsRepositoryDescription("restored"))
        coEvery { objectStorage.getInputStream(any()) } returns ByteArrayInputStream(bundleBytes)
        coEvery { repositoryRepository.create(any()) } answers { (firstArg() as Repository).copy(id = restoredId) }
        every { dfsManager.open(restoredId) } returns target

        val result = service.restore("git-backups/x.bundle", UUID.random(), "restored", "Restored")

        assertEquals("restored", result.slug)
        coVerify { repositoryRepository.updateDiskSize(restoredId, any()) }
    }

    @Test
    fun `restore rethrows and cleans up when the bundle is invalid`() = runTest {
        val restoredId = UUID.random()
        val target = InMemoryRepository(DfsRepositoryDescription("restore-fail"))
        coEvery { objectStorage.getInputStream(any()) } returns ByteArrayInputStream("not a bundle".toByteArray())
        coEvery { repositoryRepository.create(any()) } answers { (firstArg() as Repository).copy(id = restoredId) }
        every { dfsManager.open(restoredId) } returns target
        // Cleanup (purgeRepository) also fails, exercising the nested catch.
        coEvery { packRepository.findAll(restoredId) } throws RuntimeException("cleanup failed")

        assertFailsWith<Exception> {
            service.restore("git-backups/bad.bundle", UUID.random(), "x", "X")
        }
        coVerify { repositoryRepository.create(any()) }
    }

    @Test
    fun `import copies parent commits, nested trees, and shared blobs`() = runTest {
        val bareDir = File.createTempFile("git-rich-", ".git")
        bareDir.delete()
        try {
            val bare = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bareDir).call().repository
            val ins = bare.objectDatabase.newInserter()
            val blob = ins.insert(Constants.OBJ_BLOB, "shared\n".toByteArray())
            val subTreeId = ins.insert(TreeFormatter().apply { append("nested.txt", FileMode.REGULAR_FILE, blob) })
            val rootTreeId = ins.insert(TreeFormatter().apply {
                append("a.txt", FileMode.REGULAR_FILE, blob)   // shared blob referenced twice -> dedup branch
                append("sub", FileMode.TREE, subTreeId)         // nested tree -> subtree branch
            })
            val author = PersonIdent("Test", "test@example.com")
            val parentId = ins.insert(CommitBuilder().apply {
                setTreeId(rootTreeId); setAuthor(author); setCommitter(author); message = "parent"
            })
            val childId = ins.insert(CommitBuilder().apply {
                setTreeId(rootTreeId); setParentId(parentId); setAuthor(author); setCommitter(author); message = "child"
            })
            ins.flush()
            bare.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(childId); update() }
            bare.close()

            val importedId = UUID.random()
            every { dfsManager.open(importedId) } returns InMemoryRepository(DfsRepositoryDescription("imported"))
            coEvery { repositoryRepository.create(any()) } answers { (firstArg() as Repository).copy(id = importedId) }

            val result = service.importRepository(bareDir.toURI().toString(), UUID.random(), "imported", "Imported")
            assertEquals("imported", result.slug)
        } finally {
            bareDir.deleteRecursively()
        }
    }

    @Test
    fun `mirror fetch skips refs that do not resolve to commits`() = runTest {
        val bareDir = File.createTempFile("git-badref-", ".git")
        bareDir.delete()
        try {
            val bare = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bareDir).call().repository
            val ins = bare.objectDatabase.newInserter()
            val blob = ins.insert(Constants.OBJ_BLOB, "loose\n".toByteArray())
            val treeId = ins.insert(TreeFormatter().apply { append("f.txt", FileMode.REGULAR_FILE, blob) })
            val author = PersonIdent("Test", "test@example.com")
            val commitId = ins.insert(CommitBuilder().apply {
                setTreeId(treeId); setAuthor(author); setCommitter(author); message = "c"
            })
            ins.flush()
            bare.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(commitId); update() }
            // A ref pointing straight at a blob -> parseCommit throws -> the ref is skipped.
            bare.refDatabase.newUpdate("refs/heads/weird", true).apply { setNewObjectId(blob); update() }
            bare.close()

            service.mirrorFetch(repositoryId, bareDir.toURI().toString())

            coVerify { repositoryRepository.updateDiskSize(repositoryId, any()) }
        } finally {
            bareDir.deleteRecursively()
        }
    }
}
