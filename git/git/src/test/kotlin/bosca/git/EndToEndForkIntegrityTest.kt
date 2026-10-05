package bosca.git

import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
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
 * End-to-end tests verifying fork integrity. A fork copies both refs AND pack
 * data from the source repository into its own namespace. These tests verify
 * that the forked repository is a fully independent, readable copy.
 *
 * In production, pack records and pack files must be duplicated into the fork's
 * namespace during the fork operation — refs alone without pack data would leave
 * the fork as an empty shell.
 */
class EndToEndForkIntegrityTest {

    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("Forker", "fork@bosca.io")

    @BeforeTest
    fun setup() {
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
    }

    private fun openRepo(repoId: UUID): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = repoId
            this.storageAdapter = this@EndToEndForkIntegrityTest.storageAdapter
            this.refAdapter = this@EndToEndForkIntegrityTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repoId.toString())
        }.build()
    }

    /**
     * Simulates a fork by copying objects from source to fork via JGit inserter
     * and duplicating refs. This mirrors what a correct production fork must do.
     */
    private fun forkRepository(sourceId: UUID, forkId: UUID) {
        val sourceRepo = openRepo(sourceId)
        val forkRepo = openRepo(forkId)

        val sourceReader = sourceRepo.objectDatabase.newReader()
        val forkInserter = forkRepo.objectDatabase.newInserter()

        val sourceRefs = sourceRepo.refDatabase.refs
        val objectIds = mutableSetOf<ObjectId>()
        for (ref in sourceRefs) {
            if (ref.objectId != null) {
                collectReachableObjects(sourceRepo, ref.objectId, objectIds)
            }
        }

        for (oid in objectIds) {
            val loader = sourceReader.open(oid)
            forkInserter.insert(loader.type, loader.size, loader.openStream())
        }
        forkInserter.flush()

        for (ref in sourceRefs) {
            if (ref.objectId != null) {
                val refUpdate = forkRepo.refDatabase.newUpdate(ref.name, true)
                refUpdate.setNewObjectId(ref.objectId)
                refUpdate.setForceUpdate(true)
                refUpdate.update()
            }
        }

        sourceReader.close()
        forkInserter.close()
    }

    private fun collectReachableObjects(repo: BoscaDfsRepository, commitId: ObjectId, collected: MutableSet<ObjectId>) {
        if (!collected.add(commitId)) return
        val reader = repo.objectDatabase.newReader()
        val loader = reader.open(commitId)
        if (loader.type == Constants.OBJ_COMMIT) {
            val revWalk = RevWalk(repo)
            val commit = revWalk.parseCommit(commitId)
            collectTreeObjects(repo, commit.tree.id, collected)
            for (parent in commit.parents) {
                collectReachableObjects(repo, parent.id, collected)
            }
            revWalk.dispose()
        }
        reader.close()
    }

    private fun collectTreeObjects(repo: BoscaDfsRepository, treeId: ObjectId, collected: MutableSet<ObjectId>) {
        if (!collected.add(treeId)) return
        val treeWalk = TreeWalk(repo)
        treeWalk.addTree(treeId)
        while (treeWalk.next()) {
            val oid = treeWalk.getObjectId(0)
            if (treeWalk.isSubtree) {
                treeWalk.enterSubtree()
                collectTreeObjects(repo, oid, collected)
            } else {
                collected.add(oid)
            }
        }
        treeWalk.close()
    }

    @Test
    fun `forked repo is a readable independent copy`() {
        val sourceId = UUID.random()
        val forkId = UUID.random()

        val sourceRepo = openRepo(sourceId)
        val commitId = createCommit(sourceRepo, mapOf(
            "src/main.kt" to "fun main() {}\n",
            "README.md" to "# Forked Project\n"
        ), "initial commit")
        setRef(sourceRepo, "refs/heads/main", commitId)

        forkRepository(sourceId, forkId)

        val freshFork = openRepo(forkId)
        val reader = freshFork.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Fork must be able to read source commit")

        val files = readAllFiles(freshFork, commitId)
        assertEquals(2, files.size, "Fork must see all files from source")
        assertEquals("fun main() {}\n", files["src/main.kt"])
        assertEquals("# Forked Project\n", files["README.md"])
        reader.close()
    }

    @Test
    fun `forked repo preserves full commit history from source`() {
        val sourceId = UUID.random()
        val forkId = UUID.random()

        val sourceRepo = openRepo(sourceId)
        val commit1 = createCommit(sourceRepo, mapOf("file.txt" to "v1\n"), "first")
        val commit2 = createCommit(sourceRepo, mapOf("file.txt" to "v2\n"), "second", parent = commit1)
        val commit3 = createCommit(sourceRepo, mapOf("file.txt" to "v3\n"), "third", parent = commit2)
        setRef(sourceRepo, "refs/heads/main", commit3)

        forkRepository(sourceId, forkId)

        val freshFork = openRepo(forkId)
        val revWalk = RevWalk(freshFork)
        revWalk.markStart(revWalk.parseCommit(commit3))
        val history = revWalk.toList()
        assertEquals(3, history.size, "Fork must preserve full commit chain")
        assertEquals("third", history[0].fullMessage)
        assertEquals("second", history[1].fullMessage)
        assertEquals("first", history[2].fullMessage)
        revWalk.dispose()
    }

    @Test
    fun `fork with multiple branches copies all refs and data`() {
        val sourceId = UUID.random()
        val forkId = UUID.random()

        val sourceRepo = openRepo(sourceId)
        val mainCommit = createCommit(sourceRepo, mapOf("main.txt" to "main\n"), "main commit")
        val devCommit = createCommit(sourceRepo, mapOf("dev.txt" to "dev\n"), "dev commit")
        setRef(sourceRepo, "refs/heads/main", mainCommit)
        setRef(sourceRepo, "refs/heads/develop", devCommit)

        forkRepository(sourceId, forkId)

        val freshFork = openRepo(forkId)
        val mainFiles = readAllFiles(freshFork, mainCommit)
        assertEquals("main\n", mainFiles["main.txt"])

        val devFiles = readAllFiles(freshFork, devCommit)
        assertEquals("dev\n", devFiles["dev.txt"])

        val branches = freshFork.refDatabase.refs
            .filter { it.name.startsWith("refs/heads/") }
            .map { it.name }
        assertTrue(branches.contains("refs/heads/main"))
        assertTrue(branches.contains("refs/heads/develop"))
    }

    @Test
    fun `independent push to fork does not affect source`() {
        val sourceId = UUID.random()
        val forkId = UUID.random()

        val sourceRepo = openRepo(sourceId)
        val baseCommit = createCommit(sourceRepo, mapOf("file.txt" to "original\n"), "base")
        setRef(sourceRepo, "refs/heads/main", baseCommit)

        forkRepository(sourceId, forkId)

        val forkInstance = openRepo(forkId)
        val forkCommit = createCommit(forkInstance, mapOf("file.txt" to "fork modified\n"), "fork change", parent = baseCommit)
        val forkRefUpdate = forkInstance.refDatabase.newUpdate("refs/heads/main", false)
        forkRefUpdate.setNewObjectId(forkCommit)
        forkRefUpdate.setExpectedOldObjectId(baseCommit)
        forkRefUpdate.update()

        val freshSource = openRepo(sourceId)
        val sourceMainRef = freshSource.refDatabase.findRef("refs/heads/main")
        assertNotNull(sourceMainRef)
        assertEquals(baseCommit, sourceMainRef.objectId, "Source ref must be unchanged after fork push")

        val sourceFiles = readAllFiles(freshSource, baseCommit)
        assertEquals("original\n", sourceFiles["file.txt"], "Source content must be unchanged")

        val freshFork = openRepo(forkId)
        val forkMainRef = freshFork.refDatabase.findRef("refs/heads/main")
        assertEquals(forkCommit, forkMainRef!!.objectId, "Fork ref must point to new commit")

        val forkFiles = readAllFiles(freshFork, forkCommit)
        assertEquals("fork modified\n", forkFiles["file.txt"], "Fork must have new content")
    }

    @Test
    fun `fork tags are independent from source tags`() {
        val sourceId = UUID.random()
        val forkId = UUID.random()

        val sourceRepo = openRepo(sourceId)
        val commitId = createCommit(sourceRepo, mapOf("app.txt" to "release\n"), "release")
        setRef(sourceRepo, "refs/heads/main", commitId)
        setRef(sourceRepo, "refs/tags/v1.0", commitId)

        forkRepository(sourceId, forkId)

        val forkInstance = openRepo(forkId)
        val forkCommit = createCommit(forkInstance, mapOf("app.txt" to "fork release\n"), "fork release", parent = commitId)
        setRef(forkInstance, "refs/tags/v2.0-fork", forkCommit)

        val freshSource = openRepo(sourceId)
        val sourceV2 = freshSource.refDatabase.findRef("refs/tags/v2.0-fork")
        assertTrue(sourceV2 == null || sourceV2.objectId == null,
            "Fork-only tag must not appear in source")

        val freshFork = openRepo(forkId)
        val forkV1 = freshFork.refDatabase.findRef("refs/tags/v1.0")
        assertNotNull(forkV1)
        assertEquals(commitId, forkV1.objectId, "Forked v1.0 tag must point to original commit")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createCommit(
        repo: BoscaDfsRepository,
        files: Map<String, String>,
        message: String,
        parent: ObjectId? = null
    ): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val pathToBlob = files.mapValues { (_, v) ->
            inserter.insert(Constants.OBJ_BLOB, v.toByteArray())
        }
        val treeId = buildTree(inserter, pathToBlob)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndForkIntegrityTest.author
            this.committer = this@EndToEndForkIntegrityTest.author
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

    private fun setRef(repo: BoscaDfsRepository, refName: String, objectId: ObjectId) {
        val update = repo.refDatabase.newUpdate(refName, true)
        update.setNewObjectId(objectId)
        update.setForceUpdate(true)
        update.update()
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
