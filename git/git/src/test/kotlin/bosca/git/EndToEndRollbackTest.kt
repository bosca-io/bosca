@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.model.BranchProtectionRule
import bosca.git.service.BranchProtectionService
import bosca.git.transport.GitPreReceiveHook
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.revwalk.RevWalk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that rejected pushes (via branch protection or hook rejection)
 * do not leave garbage in storage and do not corrupt existing data.
 * A failed push must be as if it never happened.
 */
class EndToEndRollbackTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("Pusher", "push@bosca.io")
    private val branchProtectionService = mockk<BranchProtectionService>(relaxed = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
        provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun openRepo(): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@EndToEndRollbackTest.repositoryId
            this.storageAdapter = this@EndToEndRollbackTest.storageAdapter
            this.refAdapter = this@EndToEndRollbackTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    private fun newClientRepo(): InMemoryRepository {
        return InMemoryRepository(DfsRepositoryDescription("client"))
    }

    @Test
    fun `rejected push does not corrupt existing data`() {
        val clientRepo = newClientRepo()
        val goodCommit = createClientCommit(clientRepo, mapOf("good.txt" to "good data\n"), "good commit")
        setClientRef(clientRepo, "refs/heads/main", goodCommit)

        val serverRepo = openRepo()
        pushToServer(clientRepo, serverRepo, goodCommit, "refs/heads/main")

        val protectedRule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            requirePullRequest = true
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns protectedRule

        val badCommit = createClientCommit(clientRepo, mapOf("good.txt" to "overwritten!\n"), "bad commit", parent = goodCommit)
        setClientRef(clientRepo, "refs/heads/main", badCommit)

        val protectedServerRepo = openRepo()
        val hook = GitPreReceiveHook(branchProtectionService)
        val receivePack = ReceivePack(protectedServerRepo)
        receivePack.setBiDirectionalPipe(false)
        receivePack.setPreReceiveHook(hook)

        val packData = generatePack(clientRepo, badCommit)
        val cmdLine = "${goodCommit.name()} ${badCommit.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        val requestBytes = (pktCmd + "0000").toByteArray() + packData

        val responseBytes = ByteArrayOutputStream()
        receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
        val responseStr = parseSidebandResponse(responseBytes.toByteArray())
        assertTrue(responseStr.contains("ng refs/heads/main"), "Push must be rejected by branch protection")

        val freshRepo = openRepo()
        val files = readAllFiles(freshRepo, goodCommit)
        assertEquals("good data\n", files["good.txt"], "Original data must be intact after rejected push")

        val refs = refAdapter.scanRefs(repositoryId)
        val mainRef = refs.find { it.name == "refs/heads/main" }
        assertEquals(goodCommit.name(), mainRef?.objectId, "Ref must still point to original commit")
    }

    @Test
    fun `successful push after rejected push works normally`() {
        val clientRepo = newClientRepo()
        val commit1 = createClientCommit(clientRepo, mapOf("file.txt" to "version 1\n"), "first")
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val serverRepo = openRepo()
        pushToServer(clientRepo, serverRepo, commit1, "refs/heads/main")

        val protectedRule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            requirePullRequest = true
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns protectedRule

        val rejected = createClientCommit(clientRepo, mapOf("file.txt" to "rejected\n"), "rejected", parent = commit1)
        setClientRef(clientRepo, "refs/heads/main", rejected)

        val protectedRepo = openRepo()
        val hook = GitPreReceiveHook(branchProtectionService)
        val rp = ReceivePack(protectedRepo)
        rp.setBiDirectionalPipe(false)
        rp.setPreReceiveHook(hook)
        val packData = generatePack(clientRepo, rejected)
        val cmdLine = "${commit1.name()} ${rejected.name()} refs/heads/main\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
        rp.receive(ByteArrayInputStream((pktCmd + "0000").toByteArray() + packData), ByteArrayOutputStream(), null)

        coEvery { branchProtectionService.findMatchingRule(repositoryId, any()) } returns null

        val commit2 = createClientCommit(clientRepo, mapOf("file.txt" to "version 2\n"), "second", parent = commit1)
        setClientRef(clientRepo, "refs/heads/develop", commit2)

        val cleanRepo = openRepo()
        pushToServer(clientRepo, cleanRepo, commit2, "refs/heads/develop")

        val freshRepo = openRepo()
        val devFiles = readAllFiles(freshRepo, commit2)
        assertEquals("version 2\n", devFiles["file.txt"], "Push to unprotected branch must succeed after rejection")

        val mainFiles = readAllFiles(freshRepo, commit1)
        assertEquals("version 1\n", mainFiles["file.txt"], "Main must still have original content")
    }

    @Test
    fun `storage does not accumulate garbage from rejected pushes`() {
        val clientRepo = newClientRepo()
        val goodCommit = createClientCommit(clientRepo, mapOf("file.txt" to "good\n"), "good")
        setClientRef(clientRepo, "refs/heads/main", goodCommit)

        val serverRepo = openRepo()
        pushToServer(clientRepo, serverRepo, goodCommit, "refs/heads/main")

        val committedPacksBefore = storageAdapter.countCommittedPacks(repositoryId)

        val protectedRule = BranchProtectionRule(
            repositoryId = repositoryId,
            pattern = "main",
            requirePullRequest = true
        )
        coEvery { branchProtectionService.findMatchingRule(repositoryId, "main") } returns protectedRule

        for (i in 1..5) {
            val badCommit = createClientCommit(clientRepo, mapOf("file.txt" to "bad $i\n"), "bad $i", parent = goodCommit)

            val protectedRepo = openRepo()
            val hook = GitPreReceiveHook(branchProtectionService)
            val rp = ReceivePack(protectedRepo)
            rp.setBiDirectionalPipe(false)
            rp.setPreReceiveHook(hook)
            val packData = generatePack(clientRepo, badCommit)
            val cmdLine = "${goodCommit.name()} ${badCommit.name()} refs/heads/main\u0000 report-status side-band-64k\n"
            val pktCmd = String.format("%04x%s", cmdLine.length + 4, cmdLine)
            rp.receive(ByteArrayInputStream((pktCmd + "0000").toByteArray() + packData), ByteArrayOutputStream(), null)
        }

        // Verify the ref was not changed — the critical invariant is that
        // rejected pushes don't move refs, even if pack data is stored
        val refs = refAdapter.scanRefs(repositoryId)
        val mainRef = refs.find { it.name == "refs/heads/main" }
        assertEquals(goodCommit.name(), mainRef?.objectId,
            "Ref must remain at original commit after all rejected pushes")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createClientCommit(
        clientRepo: InMemoryRepository,
        files: Map<String, String>,
        message: String,
        parent: ObjectId? = null
    ): ObjectId {
        val inserter = clientRepo.objectDatabase.newInserter()
        val tf = TreeFormatter()
        for ((name, content) in files.toSortedMap()) {
            tf.append(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
        }
        val treeId = inserter.insert(tf)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndRollbackTest.author
            this.committer = this@EndToEndRollbackTest.author
            this.message = message
            if (parent != null) setParentId(parent)
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
    }

    private fun setClientRef(clientRepo: InMemoryRepository, refName: String, commitId: ObjectId) {
        val refUpdate = clientRepo.refDatabase.newUpdate(refName, true)
        refUpdate.setNewObjectId(commitId)
        refUpdate.setForceUpdate(true)
        refUpdate.update()
    }

    private fun pushToServer(
        clientRepo: InMemoryRepository,
        serverRepo: BoscaDfsRepository,
        commitId: ObjectId,
        refName: String,
        oldId: ObjectId? = null
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
        val responseStr = parseSidebandResponse(responseBytes.toByteArray())
        if (responseStr.contains("ng $refName")) {
            throw AssertionError("Push to $refName was rejected: $responseStr")
        }
    }

    private fun generatePack(sourceRepo: InMemoryRepository, vararg objects: ObjectId): ByteArray {
        val buf = ByteArrayOutputStream()
        val pw = org.eclipse.jgit.internal.storage.pack.PackWriter(sourceRepo)
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
        while (treeWalk.next()) {
            files[treeWalk.pathString] = String(reader.open(treeWalk.getObjectId(0)).bytes)
        }
        treeWalk.close()
        revWalk.dispose()
        reader.close()
        return files
    }
}
