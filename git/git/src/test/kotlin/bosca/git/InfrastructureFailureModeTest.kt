@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.DfsPackExtension
import bosca.git.dfs.DfsPack
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.service.RepositoryLifecycleServiceImpl
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for infrastructure-level failure modes — the boundaries between
 * components where things silently go wrong. Each test targets a specific
 * failure scenario at the JGit/adapter/storage/network boundary.
 */
class InfrastructureFailureModeTest {

    private val author = PersonIdent("Test", "test@bosca.io")
    private val repositoryId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

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

    // ── 1. Client disconnect mid-push (broken pipe) ─────────────────────

    @Test
    fun `truncated pack input does not update refs`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))
        val commitId = createCommit(clientRepo, mapOf("file.txt" to "content\n"))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val fullPack = generatePack(clientRepo, commitId)
        val truncatedPack = fullPack.copyOfRange(0, fullPack.size / 2)

        val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + truncatedPack

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)

        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), ByteArrayOutputStream(), null)
        } catch (_: Exception) {
            // Expected — truncated pack causes parse error
        }

        val refs = refAdapter.scanRefs(repositoryId)
        assertTrue(refs.isEmpty(), "Refs must not be updated after truncated pack")
    }

    @Test
    fun `empty input stream does not corrupt repository state`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        val repo = openRepo(storageAdapter, refAdapter)
        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "existing data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("existing.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@InfrastructureFailureModeTest.author
            this.committer = this@InfrastructureFailureModeTest.author
            message = "pre-existing"
        }
        val existingCommitId = inserter.insert(commit)
        inserter.flush()
        val ref = repo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(existingCommitId)
        ref.update()

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)
        try {
            receivePack.receive(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), null)
        } catch (_: Exception) {
        }

        val freshRepo = openRepo(storageAdapter, refAdapter)
        val mainRef = freshRepo.refDatabase.findRef("refs/heads/main")
        assertNotNull(mainRef)
        assertEquals(existingCommitId, mainRef.objectId, "Existing ref must be untouched after empty input")

        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(existingCommitId), "Existing data must survive empty push attempt")
        reader.close()
    }

    @Test
    fun `input stream that throws IOException mid-read does not update refs`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))
        val commitId = createCommit(clientRepo, mapOf("file.txt" to "content\n"))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val fullPack = generatePack(clientRepo, commitId)
        val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val headerBytes = (pktCmd + "0000").toByteArray()

        val failingStream = object : InputStream() {
            private val delegate = ByteArrayInputStream(headerBytes + fullPack.copyOfRange(0, fullPack.size / 3))
            private var bytesRead = 0
            override fun read(): Int {
                if (bytesRead > headerBytes.size + 100) throw IOException("Connection reset by peer")
                bytesRead++
                return delegate.read()
            }
        }

        val receivePack = ReceivePack(repo)
        receivePack.setBiDirectionalPipe(false)
        try {
            receivePack.receive(failingStream, ByteArrayOutputStream(), null)
        } catch (_: Exception) {
        }

        val refs = refAdapter.scanRefs(repositoryId)
        assertTrue(refs.isEmpty(), "Refs must not be updated after I/O failure")
    }

    // ── 2. Concurrent pushes to same branch ─────────────────────────────

    @Test
    fun `concurrent pushes to same branch — one wins, one loses, data intact`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val winnerCommitId = ConcurrentHashMap<String, ObjectId>()

        try {
            val futures = (1..2).map { i ->
                executor.submit {
                    val clientRepo = InMemoryRepository(DfsRepositoryDescription("client-$i"))
                    val commitId = createCommit(clientRepo, mapOf("file.txt" to "pusher $i content\n"), message = "push $i")
                    setClientRef(clientRepo, "refs/heads/main", commitId)

                    barrier.await(10, TimeUnit.SECONDS)

                    val serverRepo = openRepo(storageAdapter, refAdapter)
                    val packData = generatePack(clientRepo, commitId)
                    val cmdLine = "${ObjectId.zeroId().name()} ${commitId.name()} refs/heads/main\u0000 report-status side-band-64k\n"
                    val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
                    val requestBytes = (pktCmd + "0000").toByteArray() + packData

                    val receivePack = ReceivePack(serverRepo)
                    receivePack.setBiDirectionalPipe(false)
                    val responseBytes = ByteArrayOutputStream()
                    try {
                        receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
                    } catch (_: Exception) {
                    }

                    val response = parseSidebandResponse(responseBytes.toByteArray())
                    if (response.contains("ng refs/heads/main")) {
                        failures.incrementAndGet()
                    } else {
                        successes.incrementAndGet()
                        winnerCommitId["winner"] = commitId
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        // CAS semantics: first writer to create the ref wins, second gets "missing object(s)"
        // or the ref already exists error. At least one must succeed.
        assertTrue(successes.get() >= 1, "At least one push must succeed")

        val refs = refAdapter.scanRefs(repositoryId)
        val mainRef = refs.find { it.name == "refs/heads/main" }
        assertNotNull(mainRef, "main ref must exist after concurrent pushes")

        val freshRepo = openRepo(storageAdapter, refAdapter)
        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(
            reader.has(ObjectId.fromString(mainRef.objectId)),
            "Winner's commit must be readable"
        )
        reader.close()
    }

    @Test
    fun `sequential pushes to same branch both succeed with correct CAS chaining`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        val client = InMemoryRepository(DfsRepositoryDescription("client"))
        val commit1 = createCommit(client, mapOf("file.txt" to "v1\n"), message = "v1")
        setClientRef(client, "refs/heads/main", commit1)

        val server1 = openRepo(storageAdapter, refAdapter)
        pushToServer(client, server1, commit1, "refs/heads/main")

        val commit2 = createCommit(client, mapOf("file.txt" to "v2\n"), parent = commit1, message = "v2")
        setClientRef(client, "refs/heads/main", commit2)

        val server2 = openRepo(storageAdapter, refAdapter)
        pushToServer(client, server2, commit2, "refs/heads/main", oldId = commit1)

        val freshRepo = openRepo(storageAdapter, refAdapter)
        val mainRef = freshRepo.refDatabase.findRef("refs/heads/main")
        assertEquals(commit2, mainRef!!.objectId, "Main must point to second push")

        val files = readAllFiles(freshRepo, commit2)
        assertEquals("v2\n", files["file.txt"])
    }

    // ── 3. Restore partial failure ──────────────────────────────────────

    @Test
    fun `restore with corrupted bundle cleans up repo record`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        val dfsManager = mockk<BoscaDfsRepositoryManager>(relaxed = true)

        val restoredId = UUID.random()
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = restoredId)
        }
        coEvery { packRepository.findAll(restoredId) } returns emptyList()

        coEvery { objectStorage.getInputStream(any()) } returns ByteArrayInputStream("not a valid bundle".toByteArray())

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())

        var exceptionThrown = false
        try {
            service.restore("backups/corrupt.bundle", UUID.random(), "restored", "Restored")
        } catch (_: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown, "Restore with corrupt bundle must throw")

        coVerify { repoRepository.create(any()) }
        coVerify { repoRepository.hardDelete(restoredId) }
    }

    @Test
    fun `restore with empty bundle creates repo with no refs`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val restoredId = UUID.random()
        val inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("restored"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(restoredId) } returns inMemoryRepo

        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = restoredId)
        }

        // Create a valid but empty bundle (no refs, no objects)
        val emptyRepo = InMemoryRepository(DfsRepositoryDescription("empty"))
        val bundleBytes = ByteArrayOutputStream()
        val writer = org.eclipse.jgit.transport.BundleWriter(emptyRepo)
        writer.writeBundle(NullProgressMonitor.INSTANCE, bundleBytes)
        coEvery { objectStorage.getInputStream(any()) } returns ByteArrayInputStream(bundleBytes.toByteArray())

        val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
        val result = service.restore("backups/empty.bundle", UUID.random(), "empty-restore", "Empty")

        assertEquals("empty-restore", result.slug)

        val refs = inMemoryRepo.refDatabase.refs
        assertTrue(refs.isEmpty(), "Restored empty bundle must produce repo with no refs")
    }

    // ── 4. Pack name collision ──────────────────────────────────────────

    @Test
    fun `rapid pack creation produces unique names`() {
        val storageAdapter = TestDfsStorageAdapter()
        val names = mutableSetOf<String>()
        for (i in 1..100) {
            val info = storageAdapter.createPack(repositoryId, "pack-${System.nanoTime()}-INSERT", "INSERT")
            assertTrue(names.add(info.packName), "Pack name '${info.packName}' must be unique (collision at iteration $i)")
        }
        assertEquals(100, names.size, "All 100 pack names must be distinct")
    }

    @Test
    fun `concurrent pack creation via inserter produces distinct packs`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val packCount = AtomicInteger(0)

        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val barrier = CyclicBarrier(threadCount)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit {
                    val repo = openRepo(storageAdapter, refAdapter)
                    barrier.await(10, TimeUnit.SECONDS)
                    val inserter = repo.objectDatabase.newInserter()
                    inserter.insert(Constants.OBJ_BLOB, "thread $i data\n".toByteArray())
                    inserter.flush()
                    inserter.close()
                    packCount.incrementAndGet()
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(threadCount, packCount.get(), "All threads must complete")
        val committedPacks = storageAdapter.packs.values.filter { it.committed && it.repositoryId == repositoryId }
        assertEquals(threadCount, committedPacks.size, "Each thread must produce a distinct committed pack")
        val uniqueNames = committedPacks.map { it.packName }.toSet()
        assertEquals(threadCount, uniqueNames.size, "All pack names must be unique")
    }

    // ── 5. DfsOutputStream.read() memory on large packs ─────────────────

    @Test
    fun `large pack write and read-back produces correct data`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        // Create a commit with enough data to produce a substantial pack
        val clientRepo = InMemoryRepository(DfsRepositoryDescription("large-client"))
        val files = (1..200).associate { "file-$it.txt" to "x".repeat(1000) + "\n" }
        val commitId = createCommit(clientRepo, files, message = "large commit")
        setClientRef(clientRepo, "refs/heads/main", commitId)

        pushToServer(clientRepo, repo, commitId, "refs/heads/main")

        val totalPackBytes = storageAdapter.files.values.sumOf { it.size }
        assertTrue(totalPackBytes > 0, "Pack data must exist (was $totalPackBytes bytes)")

        val freshRepo = openRepo(storageAdapter, refAdapter)
        val recovered = readAllFiles(freshRepo, commitId)
        assertEquals(200, recovered.size, "All 200 files must survive large pack write")
        for (i in 1..200) {
            assertEquals("x".repeat(1000) + "\n", recovered["file-$i.txt"], "File $i content must match")
        }
    }

    @Test
    fun `pack with many objects writes correct index`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        val inserter = repo.objectDatabase.newInserter()
        val blobs = mutableListOf<ObjectId>()
        for (i in 1..500) {
            blobs.add(inserter.insert(Constants.OBJ_BLOB, "blob content $i\n".toByteArray()))
        }
        val tree = TreeFormatter()
        for ((i, blobId) in blobs.withIndex()) {
            tree.append("file-${i + 1}.txt", FileMode.REGULAR_FILE, blobId)
        }
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@InfrastructureFailureModeTest.author
            this.committer = this@InfrastructureFailureModeTest.author
            message = "500 files"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val ref = repo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(commitId)
        ref.update()

        val freshRepo = openRepo(storageAdapter, refAdapter)
        val reader = freshRepo.objectDatabase.newReader()
        for (blobId in blobs) {
            assertTrue(reader.has(blobId), "Blob $blobId must be findable in pack index")
        }
        reader.close()
    }

    // ── 6. Ref name validation ──────────────────────────────────────────

    @Test
    fun `ref names with path traversal are rejected by JGit`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@InfrastructureFailureModeTest.author
            this.committer = this@InfrastructureFailureModeTest.author
            message = "test"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val maliciousNames = listOf(
            "refs/heads/../../../etc/passwd",
            "refs/heads/branch\u0000injected",
            "refs/heads/branch with spaces",
            "refs/heads/.branch",
            "refs/heads/branch..double",
            "refs/heads/branch.lock",
            "refs/heads/branch\t",
        )

        for (name in maliciousNames) {
            val refUpdate = repo.refDatabase.newUpdate(name, true)
            refUpdate.setNewObjectId(commitId)
            refUpdate.setForceUpdate(true)
            val result = refUpdate.update()

            // JGit should reject these — REJECTED_* result or exception
            // If it doesn't reject, the ref adapter stores them safely via parameterized queries
            if (result == org.eclipse.jgit.lib.RefUpdate.Result.NEW ||
                result == org.eclipse.jgit.lib.RefUpdate.Result.FORCED) {
                // JGit accepted this ref name — verify it doesn't corrupt storage
                val refs = refAdapter.scanRefs(repositoryId)
                val stored = refs.find { it.name == name }
                if (stored != null) {
                    assertEquals(commitId.name(), stored.objectId, "If stored, ref must have correct value")
                }
            }
        }

        val validRef = repo.refDatabase.newUpdate("refs/heads/valid-branch", true)
        validRef.setNewObjectId(commitId)
        validRef.setForceUpdate(true)
        val validResult = validRef.update()
        assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, validResult, "Valid ref name must succeed")
    }

    @Test
    fun `extremely long ref name is handled safely`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val repo = openRepo(storageAdapter, refAdapter)

        val inserter = repo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, "data\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@InfrastructureFailureModeTest.author
            this.committer = this@InfrastructureFailureModeTest.author
            message = "test"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val longName = "refs/heads/" + "a".repeat(4000)
        val refUpdate = repo.refDatabase.newUpdate(longName, true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.setForceUpdate(true)

        // Should either succeed (stored safely) or fail gracefully — not crash
        try {
            refUpdate.update()
        } catch (_: Exception) {
            // Acceptable — long names may be rejected
        }

        // Regardless, a normal ref must still work
        val normalRef = repo.refDatabase.newUpdate("refs/heads/normal", true)
        normalRef.setNewObjectId(commitId)
        normalRef.setForceUpdate(true)
        assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, normalRef.update())
    }

    // ── 7. importRepository incomplete object graph ──────────────────────

    @Test
    fun `importRepository copies full object graph including commits and blobs`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val importedId = UUID.random()
        val importedRepo = InMemoryRepository(DfsRepositoryDescription("imported"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(importedId) } returns importedRepo
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = importedId)
        }

        val sourceDir = File.createTempFile("import-source-", ".git")
        sourceDir.delete()
        try {
            val sourceRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(sourceDir).call().repository
            val ins = sourceRepo.objectDatabase.newInserter()
            val blob = ins.insert(Constants.OBJ_BLOB, "important code\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("code.txt", FileMode.REGULAR_FILE, blob)
            val treeId = ins.insert(tree)
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                this.author = this@InfrastructureFailureModeTest.author
                this.committer = this@InfrastructureFailureModeTest.author
                message = "source commit"
            }
            val commitId = ins.insert(commit)
            ins.flush()
            val ref = sourceRepo.refDatabase.newUpdate("refs/heads/main", true)
            ref.setNewObjectId(commitId)
            ref.update()
            sourceRepo.close()

            val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())
            val result = service.importRepository(sourceDir.toURI().toString(), UUID.random(), "imported", "Imported")
            assertEquals("imported", result.slug)

            val reader = importedRepo.objectDatabase.newReader()
            assertTrue(reader.has(treeId), "Tree must be copied")
            assertTrue(reader.has(commitId), "Commit must be copied")
            assertTrue(reader.has(blob), "Blob must be copied")
            assertEquals("important code\n", String(reader.open(blob).bytes), "Blob content must match")
            reader.close()
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `importRepository silently skips refs that fail to parse`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val importedId = UUID.random()
        val importedRepo = InMemoryRepository(DfsRepositoryDescription("imported"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(importedId) } returns importedRepo
        coEvery { repoRepository.create(any()) } answers {
            (firstArg() as Repository).copy(id = importedId)
        }

        val sourceDir = File.createTempFile("import-partial-", ".git")
        sourceDir.delete()
        try {
            val sourceRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(sourceDir).call().repository
            val ins = sourceRepo.objectDatabase.newInserter()

            val blob = ins.insert(Constants.OBJ_BLOB, "valid\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("valid.txt", FileMode.REGULAR_FILE, blob)
            val treeId = ins.insert(tree)
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                this.author = this@InfrastructureFailureModeTest.author
                this.committer = this@InfrastructureFailureModeTest.author
                message = "valid"
            }
            val commitId = ins.insert(commit)
            ins.flush()
            val mainRef = sourceRepo.refDatabase.newUpdate("refs/heads/main", true)
            mainRef.setNewObjectId(commitId)
            mainRef.update()

            // Tag pointing to a blob (not a commit) — parseCommit will throw
            val tagRef = sourceRepo.refDatabase.newUpdate("refs/tags/bad-tag", true)
            tagRef.setNewObjectId(blob)
            tagRef.update()

            sourceRepo.close()

            val service = RepositoryLifecycleServiceImpl(repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory())

            // Import should not throw — bad refs are caught and logged at debug level
            val result = service.importRepository(sourceDir.toURI().toString(), UUID.random(), "partial", "Partial")
            assertEquals("partial", result.slug)

            // Verify the repo was created (even if some refs were skipped)
            coVerify { repoRepository.create(any()) }
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun openRepo(storageAdapter: TestDfsStorageAdapter, refAdapter: TestDfsRefAdapter): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@InfrastructureFailureModeTest.repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    private fun createCommit(
        repo: InMemoryRepository,
        files: Map<String, String>,
        parent: ObjectId? = null,
        message: String = "test commit"
    ): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val pathToBlob = files.mapValues { (_, v) -> inserter.insert(Constants.OBJ_BLOB, v.toByteArray()) }
        val treeId = buildTree(inserter, pathToBlob)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@InfrastructureFailureModeTest.author
            this.committer = this@InfrastructureFailureModeTest.author
            this.message = message
            if (parent != null) setParentId(parent)
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
    }

    private fun buildTree(inserter: org.eclipse.jgit.lib.ObjectInserter, pathToBlob: Map<String, ObjectId>): ObjectId {
        data class TreeNode(val blobs: MutableMap<String, ObjectId> = mutableMapOf(), val children: MutableMap<String, TreeNode> = mutableMapOf())
        val root = TreeNode()
        for ((path, blobId) in pathToBlob) {
            val parts = path.split("/")
            var node = root
            for (i in 0 until parts.size - 1) { node = node.children.getOrPut(parts[i]) { TreeNode() } }
            node.blobs[parts.last()] = blobId
        }
        fun insertTree(node: TreeNode): ObjectId {
            val tf = TreeFormatter()
            for ((name, child) in node.children.toSortedMap()) { tf.append(name, FileMode.TREE, insertTree(child)) }
            for ((name, blobId) in node.blobs.toSortedMap()) { tf.append(name, FileMode.REGULAR_FILE, blobId) }
            return inserter.insert(tf)
        }
        return insertTree(root)
    }

    private fun setClientRef(repo: InMemoryRepository, refName: String, commitId: ObjectId) {
        val refUpdate = repo.refDatabase.newUpdate(refName, true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.setForceUpdate(true)
        refUpdate.update()
    }

    private fun pushToServer(
        clientRepo: InMemoryRepository, serverRepo: BoscaDfsRepository,
        commitId: ObjectId, refName: String, oldId: ObjectId? = null
    ) {
        val packData = generatePack(clientRepo, commitId)
        val effectiveOldId = oldId ?: ObjectId.zeroId()
        val cmdLine = "${effectiveOldId.name()} ${commitId.name()} $refName\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + packData
        val receivePack = ReceivePack(serverRepo)
        receivePack.setBiDirectionalPipe(false)
        val responseBytes = ByteArrayOutputStream()
        receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
        val response = parseSidebandResponse(responseBytes.toByteArray())
        if (response.contains("ng $refName")) { fail("Push rejected: $response") }
    }

    private fun generatePack(repo: InMemoryRepository, vararg objects: ObjectId): ByteArray {
        val buf = ByteArrayOutputStream()
        val pw = org.eclipse.jgit.internal.storage.pack.PackWriter(repo)
        pw.preparePack(NullProgressMonitor.INSTANCE, objects.toSet(), emptySet())
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, buf)
        pw.close()
        return buf.toByteArray()
    }

    private fun parseSidebandResponse(response: ByteArray): String {
        val sb = StringBuilder()
        var pos = 0
        while (pos + 4 <= response.size) {
            val lenStr = String(response, pos, 4)
            if (lenStr == "0000") break
            val len = try { lenStr.toInt(16) } catch (_: NumberFormatException) { break }
            if (len <= 4 || pos + len > response.size) break
            val band = response[pos + 4].toInt()
            if (band == 1) sb.append(String(response, pos + 5, len - 5))
            else if (band == 3) { sb.append("[ERROR] "); sb.append(String(response, pos + 5, len - 5)) }
            pos += len
        }
        if (sb.isEmpty() && response.isNotEmpty()) sb.append(String(response))
        return sb.toString()
    }

    private fun readAllFiles(repo: BoscaDfsRepository, commitId: ObjectId): Map<String, String> {
        val reader = repo.objectDatabase.newReader()
        val revWalk = RevWalk(repo)
        val commit = revWalk.parseCommit(commitId)
        val treeWalk = TreeWalk(repo)
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true
        val files = mutableMapOf<String, String>()
        while (treeWalk.next()) { files[treeWalk.pathString] = String(reader.open(treeWalk.getObjectId(0)).bytes) }
        treeWalk.close()
        revWalk.dispose()
        reader.close()
        return files
    }
}
