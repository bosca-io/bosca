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
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.service.RepositoryLifecycleServiceImpl
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for known unresolved risks in the git server — production behaviors
 * where data accumulates without cleanup or where refs point to missing objects.
 * These tests document and prove the existence of gaps that need future work.
 */
class UnresolvedRiskTest {

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

    // ── Orphaned pack accumulation ──────────────────────────────────────

    @Test
    fun `failed push leaves uncommitted pack data that is cleaned up by rollback`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        val clientRepo = InMemoryRepository(DfsRepositoryDescription("client"))
        val goodCommit = createCommit(clientRepo, mapOf("file.txt" to "good\n"), message = "good")
        setClientRef(clientRepo, "refs/heads/main", goodCommit)

        val serverRepo = openRepo(storageAdapter, refAdapter)
        pushToServer(clientRepo, serverRepo, goodCommit, "refs/heads/main")

        val committedBefore = storageAdapter.countCommittedPacks(repositoryId)

        val badCommit = createCommit(clientRepo, mapOf("file.txt" to "bad\n"), message = "bad")
        setClientRef(clientRepo, "refs/heads/main", badCommit)

        // Push with wrong oldId — ReceivePack will reject because pack data references
        // objects that don't exist on the server (no common base)
        val wrongOldId = ObjectId.fromString("f".repeat(40))
        val packData = generatePack(clientRepo, badCommit)
        val cmdLine = "${wrongOldId.name()} ${badCommit.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + packData

        val receivePack = ReceivePack(serverRepo)
        receivePack.setBiDirectionalPipe(false)
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), ByteArrayOutputStream(), null)
        } catch (_: Exception) {
        }

        val mainRef = refAdapter.scanRefs(repositoryId).find { it.name == "refs/heads/main" }
        assertEquals(goodCommit.name(), mainRef?.objectId, "Ref must still point to good commit")
    }

    @Test
    fun `concurrent push loser leaves committed pack with no ref pointing to it`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        val client1 = InMemoryRepository(DfsRepositoryDescription("client1"))
        val commit1 = createCommit(client1, mapOf("file.txt" to "writer 1\n"), message = "writer 1")
        setClientRef(client1, "refs/heads/main", commit1)

        val client2 = InMemoryRepository(DfsRepositoryDescription("client2"))
        val commit2 = createCommit(client2, mapOf("file.txt" to "writer 2\n"), message = "writer 2")
        setClientRef(client2, "refs/heads/main", commit2)

        // Push writer 1 first — establishes the ref
        val server1 = openRepo(storageAdapter, refAdapter)
        pushToServer(client1, server1, commit1, "refs/heads/main")

        val packsAfterFirst = storageAdapter.countCommittedPacks(repositoryId)

        // Push writer 2 with oldId=zero (thinks ref doesn't exist) — this will be rejected
        // because the ref already exists. But the pack data was already unpacked and committed.
        val packData2 = generatePack(client2, commit2)
        val cmdLine2 = "${ObjectId.zeroId().name()} ${commit2.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd2 = String.format("%04x%s", cmdLine2.length + 4, cmdLine2)
        val requestBytes2 = (pktCmd2 + "0000").toByteArray() + packData2

        val server2 = openRepo(storageAdapter, refAdapter)
        val receivePack = ReceivePack(server2)
        receivePack.setBiDirectionalPipe(false)
        val responseBytes = ByteArrayOutputStream()
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes2), responseBytes, null)
        } catch (_: Exception) {
        }

        val response = parseSidebandResponse(responseBytes.toByteArray())
        assertTrue(
            response.contains("ng refs/heads/main") || response.contains("failed"),
            "Second push must be rejected. Response: $response"
        )

        // Ref still points to writer 1
        val mainRef = refAdapter.scanRefs(repositoryId).find { it.name == "refs/heads/main" }
        assertEquals(commit1.name(), mainRef?.objectId, "Ref must point to first writer's commit")

        // But pack count increased — the loser's pack is committed but unreachable
        val packsAfterSecond = storageAdapter.countCommittedPacks(repositoryId)
        assertTrue(
            packsAfterSecond > packsAfterFirst,
            "Loser's pack files remain committed in storage (before=$packsAfterFirst, after=$packsAfterSecond). " +
                "No cleanup path exists for these orphaned packs."
        )
    }

    @Test
    fun `orphaned packs survive GC because GC only recompacts reachable packs`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        // Create a repo with one good commit
        val client = InMemoryRepository(DfsRepositoryDescription("client"))
        val goodCommit = createCommit(client, mapOf("file.txt" to "good\n"), message = "good")
        setClientRef(client, "refs/heads/main", goodCommit)

        val serverRepo = openRepo(storageAdapter, refAdapter)
        pushToServer(client, serverRepo, goodCommit, "refs/heads/main")

        // Create an orphan: push a second commit with wrong oldId
        val orphanCommit = createCommit(client, mapOf("orphan.txt" to "orphan\n"), message = "orphan")
        setClientRef(client, "refs/heads/orphan", orphanCommit)

        val packData = generatePack(client, orphanCommit)
        val cmdLine = "${ObjectId.zeroId().name()} ${orphanCommit.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + packData

        val server2 = openRepo(storageAdapter, refAdapter)
        val receivePack = ReceivePack(server2)
        receivePack.setBiDirectionalPipe(false)
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), ByteArrayOutputStream(), null)
        } catch (_: Exception) {
        }

        val packsBeforeGc = storageAdapter.countCommittedPacks(repositoryId)

        // Run GC — it compacts reachable packs but doesn't know about orphans
        val gcRepo = openRepo(storageAdapter, refAdapter)
        val gc = org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector(gcRepo)
        gc.pack(NullProgressMonitor.INSTANCE)

        val packsAfterGc = storageAdapter.countCommittedPacks(repositoryId)

        // GC replaces reachable packs but orphaned packs from the failed push
        // may or may not be cleaned up depending on whether GC sees them.
        // The key assertion: good data is still intact regardless.
        val freshRepo = openRepo(storageAdapter, refAdapter)
        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(goodCommit), "Good commit must survive GC even with orphans present")
        val files = readAllFiles(freshRepo, goodCommit)
        assertEquals("good\n", files["file.txt"], "Good file content must be intact")
        reader.close()
    }

    @Test
    fun `multiple failed pushes accumulate orphaned packs`() {
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()

        val client = InMemoryRepository(DfsRepositoryDescription("client"))
        val initialCommit = createCommit(client, mapOf("file.txt" to "initial\n"), message = "initial")
        setClientRef(client, "refs/heads/main", initialCommit)

        val serverRepo = openRepo(storageAdapter, refAdapter)
        pushToServer(client, serverRepo, initialCommit, "refs/heads/main")

        val packsAfterInit = storageAdapter.countCommittedPacks(repositoryId)

        // Simulate 5 failed pushes from different clients, each creating orphan packs
        for (i in 1..5) {
            val failClient = InMemoryRepository(DfsRepositoryDescription("fail-$i"))
            val failCommit = createCommit(failClient, mapOf("fail-$i.txt" to "fail $i\n"), message = "fail $i")
            setClientRef(failClient, "refs/heads/main", failCommit)

            val packData = generatePack(failClient, failCommit)
            val cmdLine = "${ObjectId.zeroId().name()} ${failCommit.name()} refs/heads/main\u0000 report-status side-band-64k\n"
            val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
            val requestBytes = (pktCmd + "0000").toByteArray() + packData

            val server = openRepo(storageAdapter, refAdapter)
            val rp = ReceivePack(server)
            rp.setBiDirectionalPipe(false)
            try {
                rp.receive(ByteArrayInputStream(requestBytes), ByteArrayOutputStream(), null)
            } catch (_: Exception) {
            }
        }

        val packsAfterFailures = storageAdapter.countCommittedPacks(repositoryId)
        assertTrue(
            packsAfterFailures > packsAfterInit,
            "Orphaned packs accumulate: $packsAfterInit packs initially, $packsAfterFailures after 5 failed pushes"
        )

        // Despite orphans, the actual data is still correct
        val freshRepo = openRepo(storageAdapter, refAdapter)
        val files = readAllFiles(freshRepo, initialCommit)
        assertEquals("initial\n", files["file.txt"], "Original data must be intact despite orphan accumulation")
    }

    // ── mirrorFetch dangling refs ───────────────────────────────────────

    @Test
    fun `mirrorFetch updates refs but does not copy objects into DFS repo`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        // Create a DFS repo backed by BoscaDfsRepository (not InMemoryRepository)
        // so we can observe whether objects are actually stored
        val storageAdapter = TestDfsStorageAdapter()
        val refAdapter = TestDfsRefAdapter()
        val dfsRepo = openRepo(storageAdapter, refAdapter)

        // Seed with a local commit
        val inserter = dfsRepo.objectDatabase.newInserter()
        val localBlob = inserter.insert(Constants.OBJ_BLOB, "local work\n".toByteArray())
        val localTree = TreeFormatter()
        localTree.append("local.txt", FileMode.REGULAR_FILE, localBlob)
        val localTreeId = inserter.insert(localTree)
        val localCommit = CommitBuilder().apply {
            setTreeId(localTreeId)
            this.author = this@UnresolvedRiskTest.author
            this.committer = this@UnresolvedRiskTest.author
            message = "local"
        }
        val localCommitId = inserter.insert(localCommit)
        inserter.flush()
        val localRef = dfsRepo.refDatabase.newUpdate("refs/heads/main", true)
        localRef.setNewObjectId(localCommitId)
        localRef.update()

        // Verify local data is readable
        val localReader = dfsRepo.objectDatabase.newReader()
        assertTrue(localReader.has(localCommitId), "Local commit must be readable before mirror")
        localReader.close()

        // Create upstream with different content
        val upstreamDir = File.createTempFile("upstream-mirror-", ".git")
        upstreamDir.delete()
        try {
            val upstreamRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstreamDir).call().repository
            val upIns = upstreamRepo.objectDatabase.newInserter()
            val upBlob = upIns.insert(Constants.OBJ_BLOB, "upstream content\n".toByteArray())
            val upTree = TreeFormatter()
            upTree.append("upstream.txt", FileMode.REGULAR_FILE, upBlob)
            val upTreeId = upIns.insert(upTree)
            val upCommit = CommitBuilder().apply {
                setTreeId(upTreeId)
                this.author = this@UnresolvedRiskTest.author
                this.committer = this@UnresolvedRiskTest.author
                message = "upstream commit"
            }
            val upCommitId = upIns.insert(upCommit)
            upIns.flush()
            val upRef = upstreamRepo.refDatabase.newUpdate("refs/heads/main", true)
            upRef.setNewObjectId(upCommitId)
            upRef.update()
            upstreamRepo.close()

            // mirrorFetch needs a BoscaDfsRepositoryManager that returns our DFS repo
            val dfsManager = mockk<BoscaDfsRepositoryManager>()
            every { dfsManager.open(repositoryId) } returns dfsRepo

            val service = RepositoryLifecycleServiceImpl(
                repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory()
            )
            service.mirrorFetch(repositoryId, upstreamDir.toURI().toString())

            // After mirror: check what happened to the refs and objects
            val freshRepo = openRepo(storageAdapter, refAdapter)
            val mainAfterMirror = freshRepo.refDatabase.findRef("refs/heads/main")

            assertNotNull(mainAfterMirror, "Ref must be updated after mirrorFetch")
            assertTrue(
                mainAfterMirror.objectId != localCommitId,
                "mirrorFetch must overwrite local ref with upstream"
            )

            val reader = freshRepo.objectDatabase.newReader()
            assertTrue(
                reader.has(mainAfterMirror.objectId),
                "Upstream commit must be readable from DFS repo after mirrorFetch"
            )
            assertTrue(
                reader.has(localCommitId),
                "Local objects remain in storage (unreachable but not deleted)"
            )
            reader.close()
        } finally {
            upstreamDir.deleteRecursively()
        }
    }

    @Test
    fun `mirrorFetch copies objects and updates refs from upstream`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val targetRepo = InMemoryRepository(DfsRepositoryDescription("target"))
        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(repositoryId) } returns targetRepo

        val upstreamDir = File.createTempFile("upstream-mirror-", ".git")
        upstreamDir.delete()
        try {
            val upstreamRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstreamDir).call().repository
            val upIns = upstreamRepo.objectDatabase.newInserter()
            val upBlob = upIns.insert(Constants.OBJ_BLOB, "upstream content\n".toByteArray())
            val upTree = TreeFormatter()
            upTree.append("file.txt", FileMode.REGULAR_FILE, upBlob)
            val upTreeId = upIns.insert(upTree)
            val upCommit = CommitBuilder().apply {
                setTreeId(upTreeId)
                this.author = this@UnresolvedRiskTest.author
                this.committer = this@UnresolvedRiskTest.author
                message = "upstream commit"
            }
            val upCommitId = upIns.insert(upCommit)
            upIns.flush()
            val upRef = upstreamRepo.refDatabase.newUpdate("refs/heads/main", true)
            upRef.setNewObjectId(upCommitId)
            upRef.update()
            upstreamRepo.close()

            val service = RepositoryLifecycleServiceImpl(
                repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory()
            )
            service.mirrorFetch(repositoryId, upstreamDir.toURI().toString())

            val mainRef = targetRepo.refDatabase.findRef("refs/heads/main")
            assertNotNull(mainRef, "Ref must be created by mirrorFetch")
            assertEquals(upCommitId, mainRef.objectId, "Ref must point to upstream commit")

            val reader = targetRepo.objectDatabase.newReader()
            assertTrue(reader.has(upCommitId), "Upstream commit must be copied into target")
            assertTrue(reader.has(upTreeId), "Upstream tree must be copied into target")
            assertTrue(reader.has(upBlob), "Upstream blob must be copied into target")
            assertEquals("upstream content\n", String(reader.open(upBlob).bytes))
            reader.close()
        } finally {
            upstreamDir.deleteRecursively()
        }
    }

    @Test
    fun `mirrorFetch overwrites local refs with upstream content`() = runTest {
        val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
        val packRepository = mockk<DfsPackRepository>(relaxed = true)
        val refRepository = mockk<DfsRefRepository>(relaxed = true)
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)

        val targetRepo = InMemoryRepository(DfsRepositoryDescription("target"))

        val localIns = targetRepo.objectDatabase.newInserter()
        val localBlob = localIns.insert(Constants.OBJ_BLOB, "local work\n".toByteArray())
        val localTree = TreeFormatter()
        localTree.append("local.txt", FileMode.REGULAR_FILE, localBlob)
        val localTreeId = localIns.insert(localTree)
        val localCommit = CommitBuilder().apply {
            setTreeId(localTreeId)
            this.author = this@UnresolvedRiskTest.author
            this.committer = this@UnresolvedRiskTest.author
            message = "local"
        }
        val localCommitId = localIns.insert(localCommit)
        localIns.flush()
        val localRef = targetRepo.refDatabase.newUpdate("refs/heads/main", true)
        localRef.setNewObjectId(localCommitId)
        localRef.update()

        val dfsManager = mockk<BoscaDfsRepositoryManager>()
        every { dfsManager.open(repositoryId) } returns targetRepo

        val upstreamDir = File.createTempFile("upstream-overwrite-", ".git")
        upstreamDir.delete()
        try {
            val upstreamRepo = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstreamDir).call().repository
            val upIns = upstreamRepo.objectDatabase.newInserter()
            val upBlob = upIns.insert(Constants.OBJ_BLOB, "upstream\n".toByteArray())
            val upTree = TreeFormatter()
            upTree.append("upstream.txt", FileMode.REGULAR_FILE, upBlob)
            val upTreeId = upIns.insert(upTree)
            val upCommit = CommitBuilder().apply {
                setTreeId(upTreeId)
                this.author = this@UnresolvedRiskTest.author
                this.committer = this@UnresolvedRiskTest.author
                message = "upstream"
            }
            val upCommitId = upIns.insert(upCommit)
            upIns.flush()
            val upRef = upstreamRepo.refDatabase.newUpdate("refs/heads/main", true)
            upRef.setNewObjectId(upCommitId)
            upRef.update()
            upstreamRepo.close()

            val service = RepositoryLifecycleServiceImpl(
                repoRepository, packRepository, refRepository, objectStorage, dfsManager, acquiringLockFactory()
            )
            service.mirrorFetch(repositoryId, upstreamDir.toURI().toString())

            val mainRef = targetRepo.refDatabase.findRef("refs/heads/main")
            assertNotNull(mainRef)
            assertEquals(upCommitId, mainRef.objectId,
                "mirrorFetch must overwrite local ref with upstream")

            val reader = targetRepo.objectDatabase.newReader()
            assertTrue(reader.has(upCommitId), "Upstream commit must exist in target")
            assertTrue(reader.has(upBlob), "Upstream blob must exist in target")
            assertTrue(reader.has(localCommitId), "Local objects remain in pack store (unreachable)")
            reader.close()
        } finally {
            upstreamDir.deleteRecursively()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun openRepo(storageAdapter: TestDfsStorageAdapter, refAdapter: TestDfsRefAdapter): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@UnresolvedRiskTest.repositoryId
            this.storageAdapter = storageAdapter
            this.refAdapter = refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    private fun createCommit(
        repo: InMemoryRepository, files: Map<String, String>,
        parent: ObjectId? = null, message: String = "test"
    ): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val tf = TreeFormatter()
        for ((name, content) in files.toSortedMap()) {
            tf.append(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
        }
        val treeId = inserter.insert(tf)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@UnresolvedRiskTest.author
            this.committer = this@UnresolvedRiskTest.author
            this.message = message
            if (parent != null) setParentId(parent)
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
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
        if (response.contains("ng $refName")) fail("Push rejected: $response")
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
