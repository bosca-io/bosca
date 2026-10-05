package bosca.git

import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that JGit garbage collection on the Bosca DFS layer does not
 * destroy reachable objects. After GC, every commit, tree, and blob
 * reachable from any ref must still be readable — both on the same
 * instance and from a fresh one.
 */
class EndToEndGcSafetyTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("GC Test", "gc@bosca.io")

    @BeforeTest
    fun setup() {
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
    }

    private fun openRepo(): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@EndToEndGcSafetyTest.repositoryId
            this.storageAdapter = this@EndToEndGcSafetyTest.storageAdapter
            this.refAdapter = this@EndToEndGcSafetyTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    @Test
    fun `all reachable objects survive GC on same instance`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val blob1 = inserter.insert(Constants.OBJ_BLOB, "file v1\n".toByteArray())
        val tree1 = TreeFormatter()
        tree1.append("file.txt", FileMode.REGULAR_FILE, blob1)
        val tree1Id = inserter.insert(tree1)
        val commit1 = CommitBuilder().apply {
            setTreeId(tree1Id)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            message = "commit 1"
        }
        val commit1Id = inserter.insert(commit1)

        val blob2 = inserter.insert(Constants.OBJ_BLOB, "file v2\n".toByteArray())
        val tree2 = TreeFormatter()
        tree2.append("file.txt", FileMode.REGULAR_FILE, blob2)
        val tree2Id = inserter.insert(tree2)
        val commit2 = CommitBuilder().apply {
            setTreeId(tree2Id)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            setParentId(commit1Id)
            message = "commit 2"
        }
        val commit2Id = inserter.insert(commit2)

        val blob3 = inserter.insert(Constants.OBJ_BLOB, "file v3\n".toByteArray())
        val tree3 = TreeFormatter()
        tree3.append("file.txt", FileMode.REGULAR_FILE, blob3)
        val tree3Id = inserter.insert(tree3)
        val commit3 = CommitBuilder().apply {
            setTreeId(tree3Id)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            setParentId(commit2Id)
            message = "commit 3"
        }
        val commit3Id = inserter.insert(commit3)
        inserter.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(commit3Id)
        mainRef.update()

        val gc = DfsGarbageCollector(repo)
        gc.pack(NullProgressMonitor.INSTANCE)

        val reader = repo.objectDatabase.newReader()
        assertTrue(reader.has(commit1Id), "Commit 1 must survive GC")
        assertTrue(reader.has(commit2Id), "Commit 2 must survive GC")
        assertTrue(reader.has(commit3Id), "Commit 3 must survive GC")
        assertEquals("file v1\n", String(reader.open(blob1).bytes))
        assertEquals("file v2\n", String(reader.open(blob2).bytes))
        assertEquals("file v3\n", String(reader.open(blob3).bytes))
        reader.close()
    }

    @Test
    fun `all reachable objects survive GC across instances`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val blob = inserter.insert(Constants.OBJ_BLOB, "persistent content\n".toByteArray())
        val tree = TreeFormatter()
        tree.append("file.txt", FileMode.REGULAR_FILE, blob)
        val treeId = inserter.insert(tree)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            message = "gc-proof commit"
        }
        val commitId = inserter.insert(commit)
        inserter.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(commitId)
        mainRef.update()

        val gc = DfsGarbageCollector(repo)
        gc.pack(NullProgressMonitor.INSTANCE)
        repo.close()

        val freshRepo = openRepo()
        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Commit must survive GC across instances")
        assertEquals("persistent content\n", String(reader.open(blob).bytes))
        reader.close()
    }

    @Test
    fun `GC preserves multi-branch reachable objects`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            message = "base"
        }
        val baseId = inserter.insert(baseCommit)

        val mainBlob = inserter.insert(Constants.OBJ_BLOB, "main\n".toByteArray())
        val mainTree = TreeFormatter()
        mainTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        mainTree.append("main.txt", FileMode.REGULAR_FILE, mainBlob)
        val mainTreeId = inserter.insert(mainTree)
        val mainCommit = CommitBuilder().apply {
            setTreeId(mainTreeId)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            setParentId(baseId)
            message = "main advance"
        }
        val mainId = inserter.insert(mainCommit)

        val devBlob = inserter.insert(Constants.OBJ_BLOB, "dev\n".toByteArray())
        val devTree = TreeFormatter()
        devTree.append("base.txt", FileMode.REGULAR_FILE, baseBlob)
        devTree.append("dev.txt", FileMode.REGULAR_FILE, devBlob)
        val devTreeId = inserter.insert(devTree)
        val devCommit = CommitBuilder().apply {
            setTreeId(devTreeId)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            setParentId(baseId)
            message = "dev branch"
        }
        val devId = inserter.insert(devCommit)
        inserter.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(mainId)
        mainRef.update()
        val devRef = repo.refDatabase.newUpdate("refs/heads/develop", true)
        devRef.setNewObjectId(devId)
        devRef.update()

        val gc = DfsGarbageCollector(repo)
        gc.pack(NullProgressMonitor.INSTANCE)
        repo.close()

        val freshRepo = openRepo()
        val mainFiles = readAllFiles(freshRepo, mainId)
        assertEquals("base\n", mainFiles["base.txt"], "Base file must survive GC on main")
        assertEquals("main\n", mainFiles["main.txt"], "Main file must survive GC")

        val devFiles = readAllFiles(freshRepo, devId)
        assertEquals("base\n", devFiles["base.txt"], "Base file must survive GC on develop")
        assertEquals("dev\n", devFiles["dev.txt"], "Dev file must survive GC")
    }

    @Test
    fun `GC compacts multiple packs into fewer packs`() {
        val repo = openRepo()

        for (i in 1..5) {
            val inserter = repo.objectDatabase.newInserter()
            val blob = inserter.insert(Constants.OBJ_BLOB, "content $i\n".toByteArray())
            val tree = TreeFormatter()
            tree.append("file-$i.txt", FileMode.REGULAR_FILE, blob)
            val treeId = inserter.insert(tree)
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                this.author = this@EndToEndGcSafetyTest.author
                this.committer = this@EndToEndGcSafetyTest.author
                message = "commit $i"
            }
            val commitId = inserter.insert(commit)
            inserter.flush()

            val ref = repo.refDatabase.newUpdate("refs/heads/branch-$i", true)
            ref.setNewObjectId(commitId)
            ref.update()
        }

        val packsBeforeGc = storageAdapter.countCommittedPacks(repositoryId)
        assertTrue(packsBeforeGc >= 5, "Should have at least 5 packs before GC")

        val gc = DfsGarbageCollector(repo)
        gc.pack(NullProgressMonitor.INSTANCE)

        val packsAfterGc = storageAdapter.countCommittedPacks(repositoryId)
        assertTrue(packsAfterGc < packsBeforeGc, "GC must compact packs (before=$packsBeforeGc, after=$packsAfterGc)")

        repo.close()
        val freshRepo = openRepo()
        for (i in 1..5) {
            val ref = freshRepo.refDatabase.findRef("refs/heads/branch-$i")
            assertNotNull(ref, "Branch $i ref must survive GC")
            val files = readAllFiles(freshRepo, ref.objectId)
            assertEquals("content $i\n", files["file-$i.txt"], "Branch $i content must survive GC compaction")
        }
    }

    @Test
    fun `GC followed by new push works correctly`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()
        val blob1 = inserter.insert(Constants.OBJ_BLOB, "before gc\n".toByteArray())
        val tree1 = TreeFormatter()
        tree1.append("file.txt", FileMode.REGULAR_FILE, blob1)
        val tree1Id = inserter.insert(tree1)
        val commit1 = CommitBuilder().apply {
            setTreeId(tree1Id)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            message = "pre-gc commit"
        }
        val commit1Id = inserter.insert(commit1)
        inserter.flush()
        val ref = repo.refDatabase.newUpdate("refs/heads/main", true)
        ref.setNewObjectId(commit1Id)
        ref.update()

        val gc = DfsGarbageCollector(repo)
        gc.pack(NullProgressMonitor.INSTANCE)
        repo.close()

        val postGcRepo = openRepo()
        val inserter2 = postGcRepo.objectDatabase.newInserter()
        val blob2 = inserter2.insert(Constants.OBJ_BLOB, "after gc\n".toByteArray())
        val tree2 = TreeFormatter()
        tree2.append("file.txt", FileMode.REGULAR_FILE, blob2)
        val tree2Id = inserter2.insert(tree2)
        val commit2 = CommitBuilder().apply {
            setTreeId(tree2Id)
            this.author = this@EndToEndGcSafetyTest.author
            this.committer = this@EndToEndGcSafetyTest.author
            setParentId(commit1Id)
            message = "post-gc commit"
        }
        val commit2Id = inserter2.insert(commit2)
        inserter2.flush()
        val ref2 = postGcRepo.refDatabase.newUpdate("refs/heads/main", false)
        ref2.setNewObjectId(commit2Id)
        ref2.setExpectedOldObjectId(commit1Id)
        ref2.update()
        postGcRepo.close()

        val freshRepo = openRepo()
        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(commit1Id), "Pre-GC commit must still be accessible")
        assertTrue(reader.has(commit2Id), "Post-GC commit must be accessible")
        assertEquals("before gc\n", String(reader.open(blob1).bytes))
        assertEquals("after gc\n", String(reader.open(blob2).bytes))
        reader.close()
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
