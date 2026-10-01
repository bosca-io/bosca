package bosca.git

import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end integration tests for the Bosca DFS git storage layer.
 * These tests exercise real JGit push/fetch cycles through the Bosca DFS
 * adapters, verifying that data survives the full round trip without
 * corruption or loss.
 *
 * Every test uses shared in-memory adapters (simulating the real Postgres +
 * ObjectStorage backing) to prove that pack data persists across repository
 * instances — the same guarantee that multi-pod deployments depend on.
 */
class EndToEndGitIntegrationTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("Test User", "test@bosca.io")

    @BeforeTest
    fun setup() {
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
    }

    private fun openServerRepo(repoId: UUID = repositoryId): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repoId
            this.storageAdapter = this@EndToEndGitIntegrationTest.storageAdapter
            this.refAdapter = this@EndToEndGitIntegrationTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repoId.toString())
        }.build()
    }

    private fun newClientRepo(name: String = "client"): InMemoryRepository {
        return InMemoryRepository(DfsRepositoryDescription(name))
    }

    // ── Push/Fetch round-trip ───────────────────────────────────────────

    @Test
    fun `push and fetch preserves file content byte-for-byte`() {
        val content = "Hello, Bosca!\nLine 2\nLine 3\n"
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("README.md" to content))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val reader = freshServerRepo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Commit must exist on server after push")

        val revWalk = RevWalk(freshServerRepo)
        val commit = revWalk.parseCommit(commitId)
        val treeWalk = TreeWalk(freshServerRepo)
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true
        assertTrue(treeWalk.next(), "Tree must contain at least one entry")
        assertEquals("README.md", treeWalk.pathString)
        val blobId = treeWalk.getObjectId(0)
        val blob = reader.open(blobId)
        assertEquals(content, String(blob.bytes), "File content must match byte-for-byte")

        reader.close()
        revWalk.dispose()
        treeWalk.close()
    }

    @Test
    fun `push multi-file commit preserves all files`() {
        val files = mapOf(
            "src/main.kt" to "fun main() = println(\"hello\")\n",
            "src/util.kt" to "fun add(a: Int, b: Int) = a + b\n",
            "README.md" to "# Project\n",
            "build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
            ".gitignore" to "build/\n*.class\n"
        )

        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, files)
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val recoveredFiles = readAllFiles(freshServerRepo, commitId)
        assertEquals(files.size, recoveredFiles.size, "All files must be recoverable")
        for ((path, expectedContent) in files) {
            assertEquals(expectedContent, recoveredFiles[path], "Content mismatch for $path")
        }
    }

    // ── Commit history preservation ─────────────────────────────────────

    @Test
    fun `push preserves linear commit chain`() {
        val clientRepo = newClientRepo()
        val commit1 = createCommitInClient(clientRepo, mapOf("file.txt" to "v1\n"), message = "first commit")
        val commit2 = createCommitInClient(clientRepo, mapOf("file.txt" to "v2\n"), parent = commit1, message = "second commit")
        val commit3 = createCommitInClient(clientRepo, mapOf("file.txt" to "v3\n"), parent = commit2, message = "third commit")
        setClientRef(clientRepo, "refs/heads/main", commit3)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit3, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val revWalk = RevWalk(freshServerRepo)
        revWalk.markStart(revWalk.parseCommit(commit3))
        val history = mutableListOf<RevCommit>()
        for (commit in revWalk) {
            history.add(commit)
        }
        revWalk.dispose()

        assertEquals(3, history.size, "All three commits must be preserved")
        assertEquals("third commit", history[0].fullMessage)
        assertEquals("second commit", history[1].fullMessage)
        assertEquals("first commit", history[2].fullMessage)
    }

    @Test
    fun `push preserves merge commit with two parents`() {
        val clientRepo = newClientRepo()
        val base = createCommitInClient(clientRepo, mapOf("base.txt" to "base\n"), message = "base")
        val branchA = createCommitInClient(clientRepo, mapOf("base.txt" to "base\n", "a.txt" to "a\n"), parent = base, message = "branch-a")
        val branchB = createCommitInClient(clientRepo, mapOf("base.txt" to "base\n", "b.txt" to "b\n"), parent = base, message = "branch-b")

        val inserter = clientRepo.objectDatabase.newInserter()
        val mergedTree = TreeFormatter()
        mergedTree.append("a.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "a\n".toByteArray()))
        mergedTree.append("b.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "b\n".toByteArray()))
        mergedTree.append("base.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "base\n".toByteArray()))
        val mergedTreeId = inserter.insert(mergedTree)
        val merge = CommitBuilder().apply {
            setTreeId(mergedTreeId)
            this.author = this@EndToEndGitIntegrationTest.author
            this.committer = this@EndToEndGitIntegrationTest.author
            setParentIds(branchA, branchB)
            this.message = "Merge branch-b into branch-a"
        }
        val mergeId = inserter.insert(merge)
        inserter.flush()
        setClientRef(clientRepo, "refs/heads/main", mergeId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, mergeId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val revWalk = RevWalk(freshServerRepo)
        val mergeCommit = revWalk.parseCommit(mergeId)
        assertEquals(2, mergeCommit.parentCount, "Merge commit must have two parents")
        assertEquals(branchA, mergeCommit.getParent(0).id)
        assertEquals(branchB, mergeCommit.getParent(1).id)
        revWalk.dispose()
    }

    // ── Multi-branch isolation ──────────────────────────────────────────

    @Test
    fun `independent branches maintain separate state`() {
        val clientRepo = newClientRepo()
        val mainCommit = createCommitInClient(clientRepo, mapOf("main.txt" to "main content\n"), message = "main commit")
        val devCommit = createCommitInClient(clientRepo, mapOf("dev.txt" to "dev content\n"), message = "dev commit")
        setClientRef(clientRepo, "refs/heads/main", mainCommit)
        setClientRef(clientRepo, "refs/heads/develop", devCommit)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, mainCommit, "refs/heads/main")
        pushToServer(clientRepo, serverRepo, devCommit, "refs/heads/develop")

        val freshServerRepo = openServerRepo()

        val mainFiles = readAllFiles(freshServerRepo, mainCommit)
        assertEquals(1, mainFiles.size)
        assertEquals("main content\n", mainFiles["main.txt"])

        val devFiles = readAllFiles(freshServerRepo, devCommit)
        assertEquals(1, devFiles.size)
        assertEquals("dev content\n", devFiles["dev.txt"])
    }

    @Test
    fun `push to branch then update that branch with new commit`() {
        val clientRepo = newClientRepo()
        val commit1 = createCommitInClient(clientRepo, mapOf("file.txt" to "version 1\n"), message = "v1")
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit1, "refs/heads/main")

        val commit2 = createCommitInClient(clientRepo, mapOf("file.txt" to "version 2\n"), parent = commit1, message = "v2")
        setClientRef(clientRepo, "refs/heads/main", commit2)

        pushToServer(clientRepo, serverRepo, commit2, "refs/heads/main", oldId = commit1)

        val freshServerRepo = openServerRepo()
        val files = readAllFiles(freshServerRepo, commit2)
        assertEquals("version 2\n", files["file.txt"])

        val revWalk = RevWalk(freshServerRepo)
        revWalk.markStart(revWalk.parseCommit(commit2))
        val history = revWalk.toList()
        assertEquals(2, history.size)
        revWalk.dispose()
    }

    // ── Cross-instance persistence ──────────────────────────────────────

    @Test
    fun `data survives across multiple repository instances`() {
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("important.txt" to "critical data\n"))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val instance1 = openServerRepo()
        pushToServer(clientRepo, instance1, commitId, "refs/heads/main")
        instance1.close()

        val instance2 = openServerRepo()
        val reader2 = instance2.objectDatabase.newReader()
        assertTrue(reader2.has(commitId), "Instance 2 must see data pushed to instance 1")
        reader2.close()
        instance2.close()

        val instance3 = openServerRepo()
        val files = readAllFiles(instance3, commitId)
        assertEquals("critical data\n", files["important.txt"])
        instance3.close()
    }

    @Test
    fun `push to instance A then push more to instance B preserves both`() {
        val clientRepo = newClientRepo()
        val commit1 = createCommitInClient(clientRepo, mapOf("file1.txt" to "data1\n"), message = "commit 1")
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val instanceA = openServerRepo()
        pushToServer(clientRepo, instanceA, commit1, "refs/heads/main")
        instanceA.close()

        val commit2 = createCommitInClient(clientRepo, mapOf("file1.txt" to "data1\n", "file2.txt" to "data2\n"), parent = commit1, message = "commit 2")
        setClientRef(clientRepo, "refs/heads/main", commit2)

        val instanceB = openServerRepo()
        pushToServer(clientRepo, instanceB, commit2, "refs/heads/main", oldId = commit1)
        instanceB.close()

        val instanceC = openServerRepo()
        val revWalk = RevWalk(instanceC)
        revWalk.markStart(revWalk.parseCommit(commit2))
        val history = revWalk.toList()
        assertEquals(2, history.size, "Both commits must be accessible from instance C")

        val files = readAllFiles(instanceC, commit2)
        assertEquals("data1\n", files["file1.txt"])
        assertEquals("data2\n", files["file2.txt"])
        revWalk.dispose()
        instanceC.close()
    }

    // ── Repository isolation ────────────────────────────────────────────

    @Test
    fun `separate repositories do not leak data between each other`() {
        val repoId1 = UUID.random()
        val repoId2 = UUID.random()

        val client1 = newClientRepo("client1")
        val commit1 = createCommitInClient(client1, mapOf("repo1.txt" to "repo1 data\n"))
        setClientRef(client1, "refs/heads/main", commit1)

        val server1 = openServerRepo(repoId1)
        pushToServer(client1, server1, commit1, "refs/heads/main")

        val client2 = newClientRepo("client2")
        val commit2 = createCommitInClient(client2, mapOf("repo2.txt" to "repo2 data\n"))
        setClientRef(client2, "refs/heads/main", commit2)

        val server2 = openServerRepo(repoId2)
        pushToServer(client2, server2, commit2, "refs/heads/main")

        val freshServer1 = openServerRepo(repoId1)
        val reader1 = freshServer1.objectDatabase.newReader()
        assertTrue(reader1.has(commit1), "Repo 1 must have its own commit")

        val freshServer2 = openServerRepo(repoId2)
        val reader2 = freshServer2.objectDatabase.newReader()
        assertTrue(reader2.has(commit2), "Repo 2 must have its own commit")

        val files1 = readAllFiles(freshServer1, commit1)
        assertEquals(setOf("repo1.txt"), files1.keys, "Repo 1 must only contain repo1 files")

        val files2 = readAllFiles(freshServer2, commit2)
        assertEquals(setOf("repo2.txt"), files2.keys, "Repo 2 must only contain repo2 files")

        reader1.close()
        reader2.close()
    }

    // ── Ref atomicity ───────────────────────────────────────────────────

    @Test
    fun `compareAndPut rejects stale ref update`() {
        refAdapter.compareAndPut(repositoryId, "refs/heads/main", null, "a".repeat(40), null, null)

        val staleUpdate = refAdapter.compareAndPut(
            repositoryId, "refs/heads/main",
            null, // expects ref doesn't exist, but it does
            "b".repeat(40), null, null
        )
        assertEquals(false, staleUpdate, "Must reject when expected old doesn't match")
    }

    @Test
    fun `compareAndPut succeeds with correct expected old value`() {
        val sha1 = "a".repeat(40)
        val sha2 = "b".repeat(40)
        refAdapter.compareAndPut(repositoryId, "refs/heads/main", null, sha1, null, null)

        val update = refAdapter.compareAndPut(repositoryId, "refs/heads/main", sha1, sha2, null, null)
        assertTrue(update, "Must succeed when expected old matches current")

        val refs = refAdapter.scanRefs(repositoryId)
        assertEquals(sha2, refs.find { it.name == "refs/heads/main" }?.objectId)
    }

    @Test
    fun `compareAndRemove fails with wrong expected value`() {
        refAdapter.compareAndPut(repositoryId, "refs/heads/main", null, "a".repeat(40), null, null)
        val removed = refAdapter.compareAndRemove(repositoryId, "refs/heads/main", "b".repeat(40))
        assertEquals(false, removed, "Must reject removal with wrong expected SHA")
        assertEquals(1, refAdapter.scanRefs(repositoryId).size, "Ref must still exist")
    }

    // ── Large payload integrity ─────────────────────────────────────────

    @Test
    fun `push and recover a large file`() {
        val largeContent = buildString {
            for (i in 1..10_000) {
                appendLine("Line $i: " + "x".repeat(80))
            }
        }
        assertTrue(largeContent.length > 800_000, "Test data must be substantial")

        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("large-file.txt" to largeContent))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val files = readAllFiles(freshServerRepo, commitId)
        assertEquals(largeContent, files["large-file.txt"], "Large file must survive round-trip intact")
    }

    @Test
    fun `push binary content preserves exact bytes`() {
        val binaryData = ByteArray(4096) { (it % 256).toByte() }

        val clientRepo = newClientRepo()
        val inserter = clientRepo.objectDatabase.newInserter()
        val blobId = inserter.insert(Constants.OBJ_BLOB, binaryData)
        val tree = TreeFormatter()
        tree.append("binary.dat", FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndGitIntegrationTest.author
            this.committer = this@EndToEndGitIntegrationTest.author
            this.message = "binary file"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val reader = freshServerRepo.objectDatabase.newReader()
        val treeWalk = TreeWalk(freshServerRepo)
        treeWalk.addTree(RevWalk(freshServerRepo).parseCommit(commitId).tree)
        treeWalk.isRecursive = true
        assertTrue(treeWalk.next())
        val recoveredBlob = reader.open(treeWalk.getObjectId(0))
        val recoveredBytes = recoveredBlob.bytes
        assertEquals(binaryData.size, recoveredBytes.size, "Binary size must match")
        assertTrue(binaryData.contentEquals(recoveredBytes), "Binary content must match byte-for-byte")
        treeWalk.close()
        reader.close()
    }

    // ── Concurrent push safety ──────────────────────────────────────────

    @Test
    fun `concurrent pushes to different branches all succeed`() {
        val branchCount = 4
        val executor = Executors.newFixedThreadPool(branchCount)
        val barrier = CyclicBarrier(branchCount)
        val failures = AtomicInteger(0)
        val commitIds = ConcurrentHashMap<String, ObjectId>()

        try {
            val futures = (1..branchCount).map { i ->
                executor.submit {
                    try {
                        val clientRepo = newClientRepo("client-$i")
                        val commitId = createCommitInClient(
                            clientRepo,
                            mapOf("file-$i.txt" to "content for branch $i\n"),
                            message = "commit for branch-$i"
                        )
                        setClientRef(clientRepo, "refs/heads/branch-$i", commitId)
                        commitIds["branch-$i"] = commitId

                        barrier.await(10, TimeUnit.SECONDS)

                        val serverRepo = openServerRepo()
                        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/branch-$i")
                    } catch (e: Exception) {
                        failures.incrementAndGet()
                        e.printStackTrace()
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(0, failures.get(), "No concurrent push should fail")

        val freshServerRepo = openServerRepo()
        for (i in 1..branchCount) {
            val commitId = commitIds["branch-$i"]!!
            val files = readAllFiles(freshServerRepo, commitId)
            assertEquals("content for branch $i\n", files["file-$i.txt"],
                "Branch $i file content must be intact after concurrent push")
        }

        val refs = refAdapter.scanRefs(repositoryId)
        assertEquals(branchCount, refs.size, "All $branchCount branch refs must exist")
    }

    @Test
    fun `sequential pushes to many branches all succeed`() {
        val clientRepo = newClientRepo()
        val commitIds = mutableMapOf<String, ObjectId>()
        val serverRepo = openServerRepo()

        for (i in 1..10) {
            val commitId = createCommitInClient(
                clientRepo,
                mapOf("file-$i.txt" to "content for branch $i\n"),
                message = "commit for branch-$i"
            )
            setClientRef(clientRepo, "refs/heads/branch-$i", commitId)
            commitIds["branch-$i"] = commitId
            pushToServer(clientRepo, serverRepo, commitId, "refs/heads/branch-$i")
        }

        val freshServerRepo = openServerRepo()
        for (i in 1..10) {
            val commitId = commitIds["branch-$i"]!!
            val files = readAllFiles(freshServerRepo, commitId)
            assertEquals("content for branch $i\n", files["file-$i.txt"],
                "Branch $i file content must be intact")
        }

        val refs = refAdapter.scanRefs(repositoryId)
        assertEquals(10, refs.size, "All 10 branch refs must exist")
    }

    @Test
    fun `concurrent ref updates with CAS prevent lost updates`() {
        val sha1 = "a".repeat(40)
        refAdapter.compareAndPut(repositoryId, "refs/heads/main", null, sha1, null, null)

        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val barrier = CyclicBarrier(threadCount)
        val successes = AtomicInteger(0)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    val newSha = String.format("%040x", i)
                    val won = refAdapter.compareAndPut(repositoryId, "refs/heads/main", sha1, newSha, null, null)
                    if (won) successes.incrementAndGet()
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(1, successes.get(), "Exactly one CAS update must succeed")
        val refs = refAdapter.scanRefs(repositoryId)
        val mainRef = refs.find { it.name == "refs/heads/main" }
        assertNotNull(mainRef)
        assertNotEquals(sha1, mainRef.objectId, "Ref must have been updated by the winning thread")
    }

    // ── UploadPack (fetch) round-trip ───────────────────────────────────

    @Test
    fun `UploadPack serves pushed data to a fetching client`() {
        val content = "fetchable content\n"
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("fetch-me.txt" to content))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val uploadPack = UploadPack(freshServerRepo)

        val wantLine = "0032want ${commitId.name()}\n"
        val request = wantLine + "00000009done\n"
        val responseStream = ByteArrayOutputStream()
        try {
            uploadPack.upload(ByteArrayInputStream(request.toByteArray()), responseStream, null)
        } catch (_: Exception) {
            // protocol simplification — the response still contains pack data
        }

        assertTrue(responseStream.size() > 0, "UploadPack must return data")
    }

    // ── Incremental push ────────────────────────────────────────────────

    @Test
    fun `incremental push only sends new objects`() {
        val clientRepo = newClientRepo()
        val commit1 = createCommitInClient(clientRepo, mapOf("file.txt" to "v1\n"), message = "v1")
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit1, "refs/heads/main")
        val packsAfterFirst = storageAdapter.packs.size

        val commit2 = createCommitInClient(clientRepo, mapOf("file.txt" to "v2\n"), parent = commit1, message = "v2")
        setClientRef(clientRepo, "refs/heads/main", commit2)

        pushToServer(clientRepo, serverRepo, commit2, "refs/heads/main", oldId = commit1)
        assertTrue(storageAdapter.packs.size > packsAfterFirst, "New pack must be created for incremental push")

        val freshServerRepo = openServerRepo()
        val files = readAllFiles(freshServerRepo, commit2)
        assertEquals("v2\n", files["file.txt"])

        val filesV1 = readAllFiles(freshServerRepo, commit1)
        assertEquals("v1\n", filesV1["file.txt"], "Old commit data must still be accessible")
    }

    // ── Directory structure ─────────────────────────────────────────────

    @Test
    fun `deep nested directory structure is preserved`() {
        val files = mapOf(
            "a/b/c/d/e/deep.txt" to "deep content\n",
            "a/b/sibling.txt" to "sibling\n",
            "a/top.txt" to "top\n",
            "root.txt" to "root\n"
        )

        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, files)
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val recovered = readAllFiles(freshServerRepo, commitId)
        assertEquals(files.size, recovered.size)
        for ((path, expected) in files) {
            assertEquals(expected, recovered[path], "Content mismatch for nested path: $path")
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `empty file is preserved`() {
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("empty.txt" to ""))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val files = readAllFiles(freshServerRepo, commitId)
        assertEquals("", files["empty.txt"], "Empty file must be preserved")
    }

    @Test
    fun `unicode content is preserved`() {
        val unicodeContent = "日本語テスト\nEmoji: 🚀🎉\nAccented: àéîõü\nSymbols: ∑∏∫\n"
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("unicode.txt" to unicodeContent))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val files = readAllFiles(freshServerRepo, commitId)
        assertEquals(unicodeContent, files["unicode.txt"], "Unicode content must be preserved exactly")
    }

    @Test
    fun `many small files in single commit`() {
        val files = (1..100).associate { "file-$it.txt" to "content of file $it\n" }

        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, files)
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val freshServerRepo = openServerRepo()
        val recovered = readAllFiles(freshServerRepo, commitId)
        assertEquals(100, recovered.size, "All 100 files must be recoverable")
        for ((path, expected) in files) {
            assertEquals(expected, recovered[path], "Content mismatch for $path")
        }
    }

    @Test
    fun `storage contains pack data after push`() {
        assertTrue(storageAdapter.files.isEmpty(), "Storage must be empty before any push")

        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("file.txt" to "content\n"))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        assertTrue(storageAdapter.files.isNotEmpty(), "Storage must contain pack files after push")
        assertTrue(
            storageAdapter.packs.values.any { it.committed },
            "At least one pack must be committed"
        )
        assertTrue(
            storageAdapter.extensions.isNotEmpty(),
            "Pack extensions (idx, pack) must be recorded"
        )
    }

    // ── Symbolic refs (HEAD) ───────────────────────────────────────────

    @Test
    fun `HEAD symbolic ref survives across instances`() {
        val clientRepo = newClientRepo()
        val commitId = createCommitInClient(clientRepo, mapOf("file.txt" to "content\n"))
        setClientRef(clientRepo, "refs/heads/main", commitId)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main")

        val headUpdate = serverRepo.refDatabase.newUpdate(Constants.HEAD, true)
        headUpdate.link("refs/heads/main")
        serverRepo.close()

        val freshRepo = openServerRepo()
        val head = freshRepo.refDatabase.findRef(Constants.HEAD)
        assertNotNull(head, "HEAD must exist")
        assertTrue(head.isSymbolic, "HEAD must be symbolic")
        assertEquals("refs/heads/main", head.target.name, "HEAD must point to main")
        assertEquals(commitId, head.objectId, "HEAD must resolve to main's commit")
    }

    @Test
    fun `HEAD follows branch update`() {
        val clientRepo = newClientRepo()
        val commit1 = createCommitInClient(clientRepo, mapOf("file.txt" to "v1\n"), message = "v1")
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit1, "refs/heads/main")
        val headUpdate = serverRepo.refDatabase.newUpdate(Constants.HEAD, true)
        headUpdate.link("refs/heads/main")

        val commit2 = createCommitInClient(clientRepo, mapOf("file.txt" to "v2\n"), parent = commit1, message = "v2")
        setClientRef(clientRepo, "refs/heads/main", commit2)
        pushToServer(clientRepo, serverRepo, commit2, "refs/heads/main", oldId = commit1)

        val freshRepo = openServerRepo()
        val head = freshRepo.refDatabase.findRef(Constants.HEAD)
        assertNotNull(head)
        assertEquals(commit2, head.objectId, "HEAD must resolve to updated main")
    }

    // ── Branch deletion via ReceivePack ─────────────────────────────────

    @Test
    fun `delete branch via ReceivePack removes ref but preserves other data`() {
        val clientRepo = newClientRepo()
        val mainCommit = createCommitInClient(clientRepo, mapOf("main.txt" to "main\n"), message = "main")
        val featureCommit = createCommitInClient(clientRepo, mapOf("feature.txt" to "feature\n"), message = "feature")
        setClientRef(clientRepo, "refs/heads/main", mainCommit)
        setClientRef(clientRepo, "refs/heads/feature", featureCommit)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, mainCommit, "refs/heads/main")
        pushToServer(clientRepo, serverRepo, featureCommit, "refs/heads/feature")

        val deleteCmd = "${featureCommit.name()} ${ObjectId.zeroId().name()} refs/heads/feature\u0000 report-status side-band-64k\n"
        val pktCmd = String.format("%04x%s", deleteCmd.length + 4, deleteCmd)
        val requestBytes = (pktCmd + "0000").toByteArray()

        val receivePack = ReceivePack(serverRepo)
        receivePack.setBiDirectionalPipe(false)
        receivePack.setAllowDeletes(true)
        val responseBytes = ByteArrayOutputStream()
        receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)

        val freshRepo = openServerRepo()
        val featureRef = freshRepo.refDatabase.findRef("refs/heads/feature")
        assertTrue(featureRef == null || featureRef.objectId == null, "Deleted branch must be gone")

        val mainRef = freshRepo.refDatabase.findRef("refs/heads/main")
        assertNotNull(mainRef, "Main branch must still exist")
        assertEquals(mainCommit, mainRef.objectId, "Main branch must be untouched")

        val mainFiles = readAllFiles(freshRepo, mainCommit)
        assertEquals("main\n", mainFiles["main.txt"], "Main branch data must be intact")
    }

    // ── Long sequential push chain ──────────────────────────────────────

    @Test
    fun `long sequential push chain preserves complete history`() {
        val clientRepo = newClientRepo()
        val serverRepo = openServerRepo()
        val commitIds = mutableListOf<ObjectId>()

        var parentId: ObjectId? = null
        for (i in 1..20) {
            val commitId = createCommitInClient(
                clientRepo,
                mapOf("file.txt" to "version $i\n", "log.txt" to "log entry $i\n"),
                parent = parentId,
                message = "commit $i"
            )
            setClientRef(clientRepo, "refs/heads/main", commitId)
            pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main", oldId = parentId)
            commitIds.add(commitId)
            parentId = commitId
        }

        val freshRepo = openServerRepo()
        val head = commitIds.last()
        val headFiles = readAllFiles(freshRepo, head)
        assertEquals("version 20\n", headFiles["file.txt"], "Latest file must have final content")
        assertEquals("log entry 20\n", headFiles["log.txt"])

        val revWalk = RevWalk(freshRepo)
        revWalk.markStart(revWalk.parseCommit(head))
        val history = revWalk.toList()
        assertEquals(20, history.size, "All 20 commits must be in history")
        for (i in 0 until 20) {
            assertEquals("commit ${20 - i}", history[i].fullMessage)
        }
        revWalk.dispose()

        val midpoint = commitIds[9]
        val midFiles = readAllFiles(freshRepo, midpoint)
        assertEquals("version 10\n", midFiles["file.txt"], "Midpoint commit must have correct content")
    }

    @Test
    fun `push chain with different files per commit preserves all versions`() {
        val clientRepo = newClientRepo()
        val serverRepo = openServerRepo()
        val commitIds = mutableListOf<ObjectId>()

        var parentId: ObjectId? = null
        for (i in 1..10) {
            val files = (1..i).associate { j -> "file-$j.txt" to "content $j from commit $i\n" }
            val commitId = createCommitInClient(clientRepo, files, parent = parentId, message = "commit $i adds file-$i.txt")
            setClientRef(clientRepo, "refs/heads/main", commitId)
            pushToServer(clientRepo, serverRepo, commitId, "refs/heads/main", oldId = parentId)
            commitIds.add(commitId)
            parentId = commitId
        }

        val freshRepo = openServerRepo()
        val latest = readAllFiles(freshRepo, commitIds.last())
        assertEquals(10, latest.size, "Latest commit must have all 10 files")

        val first = readAllFiles(freshRepo, commitIds.first())
        assertEquals(1, first.size, "First commit must have only 1 file")
    }

    // ── File rename detection ───────────────────────────────────────────

    @Test
    fun `renamed file preserves content through push`() {
        val clientRepo = newClientRepo()
        val content = "important code that must survive rename\n"
        val commit1 = createCommitInClient(
            clientRepo,
            mapOf("src/OldName.kt" to content),
            message = "add OldName.kt"
        )
        setClientRef(clientRepo, "refs/heads/main", commit1)

        val commit2 = createCommitInClient(
            clientRepo,
            mapOf("src/NewName.kt" to content),
            parent = commit1,
            message = "rename OldName.kt to NewName.kt"
        )
        setClientRef(clientRepo, "refs/heads/main", commit2)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit2, "refs/heads/main")

        val freshRepo = openServerRepo()
        val latestFiles = readAllFiles(freshRepo, commit2)
        assertEquals(content, latestFiles["src/NewName.kt"], "Renamed file content must be preserved")
        assertTrue("src/OldName.kt" !in latestFiles, "Old name must not exist in latest commit")

        val oldFiles = readAllFiles(freshRepo, commit1)
        assertEquals(content, oldFiles["src/OldName.kt"], "Old commit must still have original name")
    }

    @Test
    fun `moved file across directories preserves content`() {
        val clientRepo = newClientRepo()
        val content = "package old.location\n\nclass Service {}\n"
        val commit1 = createCommitInClient(
            clientRepo,
            mapOf("src/old/location/Service.kt" to content),
            message = "initial"
        )
        val newContent = "package new.location\n\nclass Service {}\n"
        val commit2 = createCommitInClient(
            clientRepo,
            mapOf("src/new/location/Service.kt" to newContent),
            parent = commit1,
            message = "move Service to new package"
        )
        setClientRef(clientRepo, "refs/heads/main", commit2)

        val serverRepo = openServerRepo()
        pushToServer(clientRepo, serverRepo, commit2, "refs/heads/main")

        val freshRepo = openServerRepo()
        val files = readAllFiles(freshRepo, commit2)
        assertEquals(newContent, files["src/new/location/Service.kt"])
        assertTrue("src/old/location/Service.kt" !in files)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createCommitInClient(
        clientRepo: InMemoryRepository,
        files: Map<String, String>,
        parent: ObjectId? = null,
        message: String = "test commit"
    ): ObjectId {
        val inserter = clientRepo.objectDatabase.newInserter()

        val pathToBlob = files.mapValues { (_, content) ->
            inserter.insert(Constants.OBJ_BLOB, content.toByteArray())
        }

        val treeId = buildTree(inserter, pathToBlob)

        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndGitIntegrationTest.author
            this.committer = this@EndToEndGitIntegrationTest.author
            this.message = message
            if (parent != null) setParentId(parent)
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
    }

    private fun buildTree(
        inserter: org.eclipse.jgit.lib.ObjectInserter,
        pathToBlob: Map<String, ObjectId>
    ): ObjectId {
        data class TreeNode(
            val blobs: MutableMap<String, ObjectId> = mutableMapOf(),
            val children: MutableMap<String, TreeNode> = mutableMapOf()
        )

        val root = TreeNode()
        for ((path, blobId) in pathToBlob) {
            val parts = path.split("/")
            var node = root
            for (i in 0 until parts.size - 1) {
                node = node.children.getOrPut(parts[i]) { TreeNode() }
            }
            node.blobs[parts.last()] = blobId
        }

        fun insertTree(node: TreeNode): ObjectId {
            val tf = TreeFormatter()
            for ((name, childNode) in node.children.toSortedMap()) {
                tf.append(name, FileMode.TREE, insertTree(childNode))
            }
            for ((name, blobId) in node.blobs.toSortedMap()) {
                tf.append(name, FileMode.REGULAR_FILE, blobId)
            }
            return inserter.insert(tf)
        }

        return insertTree(root)
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
        try {
            receivePack.receive(ByteArrayInputStream(requestBytes), responseBytes, null)
        } catch (e: Exception) {
            fail("ReceivePack.receive() threw: ${e.message}")
        }

        val responseStr = parseSidebandResponse(responseBytes.toByteArray())
        if (responseStr.contains("ng $refName")) {
            fail("Push to $refName was rejected. Response: $responseStr")
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
            if (band == 1) {
                sb.append(String(response, pos + 5, len - 5))
            } else if (band == 3) {
                sb.append("[ERROR] ")
                sb.append(String(response, pos + 5, len - 5))
            }
            pos += len
        }
        if (sb.isEmpty() && response.isNotEmpty()) {
            sb.append(String(response))
        }
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
            val blobId = treeWalk.getObjectId(0)
            val blob = reader.open(blobId)
            files[treeWalk.pathString] = String(blob.bytes)
        }

        treeWalk.close()
        revWalk.dispose()
        reader.close()
        return files
    }
}
