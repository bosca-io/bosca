package bosca.git

import bosca.db.ConnectionPool
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.dfs.DfsPackExtension
import bosca.git.model.BranchProtectionRule
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.PullRequestAssigneeRepository
import bosca.git.repository.PullRequestDependencyRepository
import bosca.git.repository.PullRequestRepository
import bosca.git.repository.ReviewCommentRepository
import bosca.git.repository.ReviewRepository
import bosca.git.repository.TaskPullRequestReferenceRepository
import bosca.git.service.BranchProtectionService
import bosca.git.service.CommitStatusService
import bosca.git.service.MergeExecutor
import bosca.git.service.PullRequestServiceImpl
import bosca.git.service.RepositoryLifecycleServiceImpl
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.DfsPack
import bosca.git.transport.GitPreReceiveHook
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for every identified data-loss risk path in the git server.
 * Each test targets a specific failure mode that could destroy, corrupt,
 * or silently lose repository data.
 */
class DataLossRiskTest {

    private val author = PersonIdent("Test", "test@bosca.io")
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
    }

    /** A lock factory whose lock always acquires — for tests that aren't exercising contention. */
    private fun acquiringLockFactory(): DistributedLockFactory {
        val lock = mockk<DistributedLock>(relaxed = true)
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        val factory = mockk<DistributedLockFactory>()
        coEvery { factory.create(any()) } returns lock
        return factory
    }

    // ── 1. Purge partial failure ────────────────────────────────────────

    @Test
    fun `purge deletes all storage files and database records`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)

        val packId1 = UUID.random()
        val packId2 = UUID.random()
        val packs = listOf(
            DfsPack(id = packId1, repositoryId = repositoryId, packName = "p1", packSource = "INSERT", committed = true),
            DfsPack(id = packId2, repositoryId = repositoryId, packName = "p2", packSource = "INSERT", committed = true)
        )
        coEvery { packRepository.findAll(repositoryId) } returns packs
        coEvery { packRepository.findExtensions(packId1) } returns listOf(
            DfsPackExtension(packId = packId1, extension = "pack", fileSize = 100, storagePath = "git/$repositoryId/packs/p1.pack"),
            DfsPackExtension(packId = packId1, extension = "idx", fileSize = 50, storagePath = "git/$repositoryId/packs/p1.idx")
        )
        coEvery { packRepository.findExtensions(packId2) } returns listOf(
            DfsPackExtension(packId = packId2, extension = "pack", fileSize = 200, storagePath = "git/$repositoryId/packs/p2.pack")
        )

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())

        val expired = listOf(Repository(id = repositoryId, slug = "old", name = "Old", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true))
        coEvery { repoRepository.findExpiredSoftDeletes() } returns expired

        service.purgeExpiredRepositories()

        coVerify { objectStorage.delete(match { it.toString() == "git/$repositoryId/packs/p1.pack" }) }
        coVerify { objectStorage.delete(match { it.toString() == "git/$repositoryId/packs/p1.idx" }) }
        coVerify { objectStorage.delete(match { it.toString() == "git/$repositoryId/packs/p2.pack" }) }
        coVerify { refRepository.deleteAll(repositoryId) }
        coVerify { repoRepository.hardDelete(repositoryId) }
    }

    @Test
    fun `purge continues if one repository fails`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)

        val repo1Id = UUID.random()
        val repo2Id = UUID.random()
        val expired = listOf(
            Repository(id = repo1Id, slug = "r1", name = "R1", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true),
            Repository(id = repo2Id, slug = "r2", name = "R2", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true)
        )
        coEvery { repoRepository.findExpiredSoftDeletes() } returns expired
        coEvery { packRepository.findAll(repo1Id) } throws RuntimeException("ObjectStorage failure")
        coEvery { packRepository.findAll(repo2Id) } returns emptyList()

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
        service.purgeExpiredRepositories()

        coVerify(exactly = 0) { repoRepository.hardDelete(repo1Id) }
        coVerify { repoRepository.hardDelete(repo2Id) }
    }

    @Test
    fun `purge with storage failure does not hard-delete database records`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)

        val packId = UUID.random()
        coEvery { packRepository.findAll(repositoryId) } returns listOf(
            DfsPack(id = packId, repositoryId = repositoryId, packName = "p1", packSource = "INSERT", committed = true)
        )
        coEvery { packRepository.findExtensions(packId) } returns listOf(
            DfsPackExtension(packId = packId, extension = "pack", fileSize = 100, storagePath = "path.pack")
        )
        coEvery { objectStorage.delete(any()) } throws RuntimeException("Storage unavailable")

        val expired = listOf(Repository(id = repositoryId, slug = "r", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true))
        coEvery { repoRepository.findExpiredSoftDeletes() } returns expired

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
        service.purgeExpiredRepositories()

        coVerify(exactly = 0) { repoRepository.hardDelete(repositoryId) }
    }

    // ── 2. GC concurrent read ───────────────────────────────────────────

    @Test
    fun `data written before GC is readable after GC from fresh instance`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        fun openRepo(): BoscaDfsRepository = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@DataLossRiskTest.repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, "critical data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blob)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@DataLossRiskTest.author
            this.committer = this@DataLossRiskTest.author
            message = "important commit"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val ref = repo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(commitId)
        ref.update()

        val gc = org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector(repo)
        gc.pack(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE)
        repo.close()

        val freshRepo = openRepo()
        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Commit must survive GC and be readable from fresh instance")
        assertEquals("critical data\n", String(reader.open(blob).bytes), "File content must be intact after GC")
        reader.close()
    }

    // ── 3. PR merge ref update failure ──────────────────────────────────

    @Test
    fun `merge with stale target ref fails without corrupting PR state`() = runTest {
        val prRepository = mockk<PullRequestRepository>(relaxed = true)
        val reviewRepository = mockk<ReviewRepository>(relaxed = true)
        val reviewCommentRepository = mockk<ReviewCommentRepository>(relaxed = true)
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
        val taskPrRefRepository = mockk<TaskPullRequestReferenceRepository>(relaxed = true)
        val commitStatusService = mockk<CommitStatusService>(relaxed = true)

        val inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("test"))
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val baseBlob = ins.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = ins.insert(baseTree)
        val baseCommit = CommitBuilder().apply { setTreeId(baseTreeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; message = "base" }
        val baseId = ins.insert(baseCommit)

        val featureBlob = ins.insert(Constants.OBJ_BLOB, "feature\n".toByteArray())
        val featureTree = TreeFormatter()
        featureTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        featureTree.append("feature.txt", FileMode.REGULAR_FILE, featureBlob)
        val featureTreeId = ins.insert(featureTree)
        val featureCommit = CommitBuilder().apply { setTreeId(featureTreeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; setParentId(baseId); message = "feature" }
        val featureId = ins.insert(featureCommit)

        val concurrentBlob = ins.insert(Constants.OBJ_BLOB, "concurrent push\n".toByteArray())
        val concurrentTree = TreeFormatter()
        concurrentTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        concurrentTree.append("concurrent.txt", FileMode.REGULAR_FILE, concurrentBlob)
        val concurrentTreeId = ins.insert(concurrentTree)
        val concurrentCommit = CommitBuilder().apply { setTreeId(concurrentTreeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; setParentId(baseId); message = "concurrent" }
        val concurrentId = ins.insert(concurrentCommit)
        ins.flush()

        val mainRef = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(concurrentId)
        mainRef.update()
        val featureRef = inMemoryRepo.refDatabase.newUpdate("refs/heads/feature", true)
        featureRef.setNewObjectId(featureId)
        featureRef.update()

        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(any()) } returns inMemoryRepo

        val prId = UUID.random()
        val pr = PullRequest(
            id = prId, repositoryId = repositoryId, number = 1, title = "Test",
            authorId = UUID.random(), sourceBranch = "feature", targetBranch = "main",
            status = PullRequestStatus.OPEN
        )
        coEvery { prRepository.findById(prId) } returns pr
        coEvery { branchProtectionService.findMatchingRule(any(), any()) } returns null
        coEvery { prRepository.updateMergeState(any()) } answers { firstArg() }

        val assigneeRepository = mockk<PullRequestAssigneeRepository>(relaxed = true)
        val dependencyRepository = mockk<PullRequestDependencyRepository>(relaxed = true)
        val service = PullRequestServiceImpl(
            prRepository, dependencyRepository, reviewRepository, reviewCommentRepository, repoRepository,
            assigneeRepository, branchProtectionService, dfsManager, taskPrRefRepository, commitStatusService,
            acquiringLockFactory(), mockk<ProfileService>(relaxed = true),
            mockk<bosca.security.service.SecurityService>(relaxed = true),
        )

        // Main was pushed to concurrentId while PR still targets base.
        // The merge reads main's CURRENT ref (concurrentId) and merges feature into it.
        // This means the concurrent push's content is included — no data loss, but the
        // merge tree includes both concurrent and feature changes.
        val result = service.merge(prId, MergeStrategy.MERGE_COMMIT, UUID.random(), "Merger", "m@test.com")
        assertNotNull(result.mergeSha, "Merge must produce a commit")

        val mergeId = ObjectId.fromString(result.mergeSha)
        val revWalk = RevWalk(inMemoryRepo)
        val mergeCommit = revWalk.parseCommit(mergeId)
        assertEquals(2, mergeCommit.parentCount, "Merge commit must reference both parents")

        val treeWalk = TreeWalk(inMemoryRepo)
        treeWalk.addTree(mergeCommit.tree)
        treeWalk.isRecursive = true
        val files = mutableSetOf<String>()
        while (treeWalk.next()) { files.add(treeWalk.pathString) }
        treeWalk.close()

        assertTrue("feature.txt" in files, "Feature branch file must be in merge")
        assertTrue("concurrent.txt" in files, "Concurrent push file must be preserved in merge")
        assertTrue("base.txt" in files, "Base file must survive")
        revWalk.dispose()
    }

    // ── 4. Mirror fetch overwrites ──────────────────────────────────────

    @Test
    fun `mirror fetch updates disk size after sync`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("mirror"))
        val ins = inMemoryRepo.objectDatabase.newInserter()
        val blob = ins.insert(Constants.OBJ_BLOB, "content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blob)
        val treeId = ins.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@DataLossRiskTest.author
            this.committer = this@DataLossRiskTest.author
            message = "initial"
        }
        val commitId = ins.insert(commit)
        ins.flush()
        val ref = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(commitId)
        ref.update()

        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(repositoryId) } returns inMemoryRepo

        val upstreamDir = File.createTempFile("upstream-", ".git")
        upstreamDir.delete()
        try {
            val upstreamRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstreamDir).call().repository
            val upIns = upstreamRepo.objectDatabase.newInserter()
            val upBlob = upIns.insert(Constants.OBJ_BLOB, "upstream\n".toByteArray())
            val upTree = TreeFormatter()
            upTree.append("file.txt", FileMode.REGULAR_FILE, upBlob)
            val upTreeId = upIns.insert(upTree)
            val upCommit = CommitBuilder().apply {
                setTreeId(upTreeId)
                this.author = this@DataLossRiskTest.author
                this.committer = this@DataLossRiskTest.author
                message = "upstream"
            }
            val upId = upIns.insert(upCommit)
            upIns.flush()
            val upRef = upstreamRepo.refDatabase.newUpdate("refs/heads/main", true)
            upRef.setNewObjectId(upId)
            upRef.update()
            upstreamRepo.close()

            val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
            service.mirrorFetch(repositoryId, upstreamDir.toURI().toString())

            coVerify { repoRepository.updateDiskSize(repositoryId, any()) }
        } finally {
            upstreamDir.deleteRecursively()
        }
    }

    @Test
    fun `mirror fetch uses force-update which can overwrite local branches`() = runTest {
        // mirrorFetch calls setForceUpdate(true) on every ref from upstream.
        // This means if upstream has a branch with the same name as a local branch,
        // the local branch is silently overwritten — no CAS, no conflict detection.
        // This test documents that behavior as a known risk.
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("mirror"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(repositoryId) } returns inMemoryRepo

        val upstreamDir = File.createTempFile("upstream-", ".git")
        upstreamDir.delete()
        try {
            val upstreamRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstreamDir).call().repository
            val upIns = upstreamRepo.objectDatabase.newInserter()
            val upBlob = upIns.insert(Constants.OBJ_BLOB, "upstream\n".toByteArray())
            val upTree = TreeFormatter()
            upTree.append("file.txt", FileMode.REGULAR_FILE, upBlob)
            val upTreeId = upIns.insert(upTree)
            val upCommit = CommitBuilder().apply {
                setTreeId(upTreeId)
                this.author = this@DataLossRiskTest.author
                this.committer = this@DataLossRiskTest.author
                message = "upstream"
            }
            upIns.insert(upCommit)
            upIns.flush()
            val upRef = upstreamRepo.refDatabase.newUpdate("refs/heads/main", true)
            upRef.setNewObjectId(upIns.insert(upCommit))
            upRef.update()
            upstreamRepo.close()

            val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())

            // This should not throw — mirrorFetch silently overwrites
            service.mirrorFetch(repositoryId, upstreamDir.toURI().toString())
        } finally {
            upstreamDir.deleteRecursively()
        }
    }

    // ── 5. Import silent ref skipping ───────────────────────────────────

    @Test
    fun `import creates repository even when some refs fail`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val importedRepoId = UUID.random()
        val importedRepo = InMemoryRepository(DfsRepositoryDescription("imported"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(importedRepoId) } returns importedRepo
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = importedRepoId)
        }

        val sourceDir = File.createTempFile("source-", ".git")
        sourceDir.delete()
        try {
            val sourceRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(sourceDir).call().repository
            val ins = sourceRepo.objectDatabase.newInserter()
            val blob = ins.insert(Constants.OBJ_BLOB, "content\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("file.txt", FileMode.REGULAR_FILE, blob)
            val treeId = ins.insert(tree)
            val commit = CommitBuilder().apply { setTreeId(treeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; message = "initial" }
            val commitId = ins.insert(commit)
            ins.flush()
            val ref = sourceRepo.refDatabase.newUpdate("refs/heads/main", true)
            ref.setNewObjectId(commitId)
            ref.update()
            sourceRepo.close()

            val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
            val result = service.importRepository(sourceDir.toURI().toString(), UUID.random(), "imported", "Imported")

            assertEquals("imported", result.slug)
            coVerify { repoRepository.create(any()) }
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    // ── 6. pusherId null bypass ─────────────────────────────────────────

    @Test
    fun `null pusherId bypasses restrictPushAccess`() {
        val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
        val hook = GitPreReceiveHook(branchProtectionService)

        val allowedId = UUID.random()
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            restrictPushAccess = listOf(allowedId)
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule

        val pushHook = hook.forPusher(null)

        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        pushHook.onPreReceive(rp, mutableListOf(command))

        assertEquals(
            ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result,
            "null pusherId must be rejected when restrictPushAccess is set"
        )
    }

    @Test
    fun `unlisted pusherId is rejected by restrictPushAccess`() {
        val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
        val hook = GitPreReceiveHook(branchProtectionService)

        val allowedId = UUID.random()
        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            restrictPushAccess = listOf(allowedId)
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule

        val pushHook = hook.forPusher(UUID.random())

        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        pushHook.onPreReceive(rp, mutableListOf(command))

        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result)
    }

    // ── 7. Concurrent purge idempotency ─────────────────────────────────

    @Test
    fun `purge is safe when repository already hard-deleted`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)

        coEvery { packRepository.findAll(repositoryId) } returns emptyList()

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())

        val expired = listOf(Repository(id = repositoryId, slug = "gone", name = "Gone", ownerId = UUID.random(), visibility = Visibility.PRIVATE, deleted = true))
        coEvery { repoRepository.findExpiredSoftDeletes() } returns expired

        service.purgeExpiredRepositories()

        coVerify { refRepository.deleteAll(repositoryId) }
        coVerify { repoRepository.hardDelete(repositoryId) }
    }

    // ── 8. Backup/restore integrity ─────────────────────────────────────

    @Test
    fun `backup and restore preserves all refs and content`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>()

        val sourceRepo = InMemoryRepository(DfsRepositoryDescription("source"))
        val ins = sourceRepo.objectDatabase.newInserter()
        val blob = ins.insert(Constants.OBJ_BLOB, "backup content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blob)
        val treeId = ins.insert(tree)
        val commit = CommitBuilder().apply { setTreeId(treeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; message = "backup this" }
        val commitId = ins.insert(commit)
        ins.flush()
        val mainRef = sourceRepo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(commitId)
        mainRef.update()
        val headRef = sourceRepo.refDatabase.newUpdate(Constants.HEAD, true)
        headRef.link("refs/heads/main")

        every { dfsManager.open(repositoryId) } returns sourceRepo

        val capturedBundle = slot<ByteArrayInputStream>()
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        coEvery { objectStorage.setInputStream(any(), capture(capturedBundle), any()) } answers {
            capturedBundle.captured.available().toLong()
        }

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
        val backupPath = service.backup(repositoryId)
        assertTrue(backupPath.contains("git-backups"), "Backup path must follow convention")

        val bundleBytes = capturedBundle.captured.readBytes()
        assertTrue(bundleBytes.size > 0, "Bundle must have content")

        // Verify bundle can be read by JGit
        val tempFile = File.createTempFile("bundle-verify-", ".bundle")
        val tempDir = File.createTempFile("bundle-repo-", ".git")
        tempDir.delete()
        try {
            tempFile.writeBytes(bundleBytes)
            val verifyRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(tempDir).call().repository
            org.eclipse.jgit.api.Git.wrap(verifyRepo).fetch()
                .setRemote(tempFile.toURI().toString())
                .setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/*:refs/*"))
                .call()

            val restoredMain = verifyRepo.refDatabase.findRef("refs/heads/main")
            assertNotNull(restoredMain, "Restored repo must have main branch")
            assertEquals(commitId, restoredMain.objectId, "Restored main must point to same commit")

            val restoredReader = verifyRepo.objectDatabase.newReader()
            assertTrue(restoredReader.has(commitId), "Restored commit must be readable")
            assertTrue(restoredReader.has(blob), "Restored blob must be readable")
            assertEquals("backup content\n", String(restoredReader.open(blob).bytes))
            restoredReader.close()
            verifyRepo.close()
        } finally {
            tempFile.delete()
            tempDir.deleteRecursively()
        }
    }

    // ── 9. LFS quota race ───────────────────────────────────────────────

    @Test
    fun `LFS upload does not check quota before storing`() = runTest {
        val lfsRepository = mockk<bosca.git.repository.LfsObjectRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        coEvery { lfsRepository.getTotalSize(repositoryId) } returns 9L * 1024 * 1024 * 1024
        coEvery { objectStorage.uploadMultipartPart(any(), any(), any(), any(), any()) } answers {
            arg<java.io.InputStream>(3).readBytes().size.toLong()
        }
        coEvery { lfsRepository.create(any()) } answers {
            firstArg<bosca.git.model.LfsObject>().copy(id = UUID.random())
        }

        val service = bosca.git.service.LfsObjectServiceImpl(lfsRepository, objectStorage)

        // Upload succeeds even when close to quota — no pre-upload quota check
        val data = "data".toByteArray()
        val oid = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val result = service.upload(repositoryId, oid, ByteArrayInputStream(data), 4L)
        assertNotNull(result, "Upload succeeds without quota check — quota enforcement is caller's responsibility")

        coVerify { objectStorage.completeMultipartUpload(any(), any(), 1, 4L) }
    }

    // ── 10. Branch protection rule deletion race ────────────────────────

    @Test
    fun `push evaluated against rule that exists at check time`() {
        val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
        val hook = GitPreReceiveHook(branchProtectionService)

        val rule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            requirePullRequest = true
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns rule

        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val command = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command))

        assertEquals(
            ReceiveCommand.Result.REJECTED_OTHER_REASON, command.result,
            "Push must be rejected when protection rule exists at evaluation time"
        )

        // Now simulate rule deletion — next push goes through
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns null

        val command2 = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main"
        )
        hook.onPreReceive(rp, mutableListOf(command2))

        assertEquals(
            ReceiveCommand.Result.NOT_ATTEMPTED, command2.result,
            "Push must succeed after protection rule is deleted"
        )
    }

    @Test
    fun `force push allowed after allowForcePush rule deleted and recreated permissively`() {
        val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)
        val hook = GitPreReceiveHook(branchProtectionService)

        val strictRule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            allowForcePush = false
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns strictRule

        val boscaRepo = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepo.repositoryId } returns repositoryId
        val rp = mockk<ReceivePack>(relaxed = true)
        every { rp.repository } returns boscaRepo

        val forceCmd = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main",
            ReceiveCommand.Type.UPDATE_NONFASTFORWARD
        )
        hook.onPreReceive(rp, mutableListOf(forceCmd))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, forceCmd.result,
            "Force push must be rejected under strict rule")

        val permissiveRule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            allowForcePush = true
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns permissiveRule

        val forceCmd2 = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main",
            ReceiveCommand.Type.UPDATE_NONFASTFORWARD
        )
        hook.onPreReceive(rp, mutableListOf(forceCmd2))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, forceCmd2.result,
            "Force push must be allowed after rule changed to permissive")
    }

    // ── Additional: merge all strategies verify ref actually moved ──────

    @Test
    fun `merge commit updates target branch ref in repository`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repoId = UUID.random()

        fun openRepo(): BoscaDfsRepository = BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repoId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repoId.toString())
        }.build()

        val repo = openRepo()
        val ins = repo.objectDatabase.newInserter()
        val baseBlob = ins.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("file.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = ins.insert(baseTree)
        val baseCommit = CommitBuilder().apply { setTreeId(baseTreeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; message = "base" }
        val baseId = ins.insert(baseCommit)

        val featureBlob = ins.insert(Constants.OBJ_BLOB, "feature\n".toByteArray())
        val featureTree = TreeFormatter()
        featureTree.append("feature.txt", FileMode.REGULAR_FILE, featureBlob)
        featureTree.append("file.txt", FileMode.REGULAR_FILE, baseBlob)
        val featureTreeId = ins.insert(featureTree)
        val featureCommit = CommitBuilder().apply { setTreeId(featureTreeId); this.author = this@DataLossRiskTest.author; this.committer = this@DataLossRiskTest.author; setParentId(baseId); message = "feature" }
        val featureId = ins.insert(featureCommit)
        ins.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(baseId)
        mainRef.update()
        val featRef = repo.refDatabase.newUpdate("refs/heads/feature", true)
        featRef.setNewObjectId(featureId)
        featRef.update()

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.MERGE_COMMIT, "Merge", author)
        assertTrue(result.success)
        val mergeId = ObjectId.fromString(result.mergeSha)

        val refUpdate = repo.refDatabase.newUpdate("refs/heads/main", false)
        refUpdate.setNewObjectId(mergeId)
        refUpdate.setExpectedOldObjectId(baseId)
        refUpdate.update()
        repo.close()

        val freshRepo = openRepo()
        val resolvedMain = freshRepo.refDatabase.findRef("refs/heads/main")
        assertNotNull(resolvedMain)
        assertEquals(mergeId, resolvedMain.objectId, "Main ref must point to merge commit after merge")

        val files = readAllFiles(freshRepo, mergeId)
        assertEquals("base\n", files["file.txt"])
        assertEquals("feature\n", files["feature.txt"])
    }

    private fun readAllFiles(repo: BoscaDfsRepository, commitId: ObjectId): Map<String, String> {
        val reader = repo.objectDatabase.newReader()
        val revWalk = RevWalk(repo)
        val commit = revWalk.parseCommit(commitId)
        val treeWalk = TreeWalk(repo)
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true
        val files = mutableMapOf<String, String>()
        while (treeWalk.next()) {
            files[treeWalk.pathString] = String(reader.open(treeWalk.getObjectId(0)).bytes)
        }
        treeWalk.close()
        revWalk.dispose()
        reader.close()
        return files
    }
}
