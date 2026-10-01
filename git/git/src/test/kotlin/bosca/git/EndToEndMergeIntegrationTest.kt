package bosca.git

import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.model.MergeStrategy
import bosca.git.service.MergeExecutor
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end merge integration tests. These push real branches to the Bosca
 * DFS layer, execute merges via [MergeExecutor], update refs, and then verify
 * the merged result is fully readable from a fresh repository instance.
 *
 * This covers the critical path: branch A + branch B → merge → ref update
 * → data accessible. If this breaks, merged PRs lose code.
 */
class EndToEndMergeIntegrationTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("Merger", "merge@bosca.io")

    @BeforeTest
    fun setup() {
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
    }

    private fun openRepo(): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@EndToEndMergeIntegrationTest.repositoryId
            this.storageAdapter = this@EndToEndMergeIntegrationTest.storageAdapter
            this.refAdapter = this@EndToEndMergeIntegrationTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    // ── Merge commit strategy ───────────────────────────────────────────

    @Test
    fun `merge commit preserves files from both branches`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "shared\n".toByteArray())
        val baseOnlyBlob = inserter.insert(Constants.OBJ_BLOB, "base content\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("base-only.txt", FileMode.REGULAR_FILE, baseOnlyBlob)
        baseTree.append("shared.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "base commit"
        }
        val baseId = inserter.insert(baseCommit)

        // Feature branch: keeps all base files and adds a new one
        val featureBlob = inserter.insert(Constants.OBJ_BLOB, "feature content\n".toByteArray())
        val featureTree = TreeFormatter()
        featureTree.append("base-only.txt", FileMode.REGULAR_FILE, baseOnlyBlob)
        featureTree.append("feature.txt", FileMode.REGULAR_FILE, featureBlob)
        featureTree.append("shared.txt", FileMode.REGULAR_FILE, baseBlob)
        val featureTreeId = inserter.insert(featureTree)
        val featureCommit = CommitBuilder().apply {
            setTreeId(featureTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "feature commit"
        }
        val featureId = inserter.insert(featureCommit)
        inserter.flush()

        setRef(repo, "refs/heads/main", baseId)
        setRef(repo, "refs/heads/feature", featureId)

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.MERGE_COMMIT, "Merge feature", author)
        assertTrue(result.success, "Merge must succeed")
        assertNotNull(result.mergeSha)

        val mergeCommitId = ObjectId.fromString(result.mergeSha)
        updateRef(repo, "refs/heads/main", mergeCommitId, baseId)

        val freshRepo = openRepo()
        val files = readAllFiles(freshRepo, mergeCommitId)
        assertEquals("shared\n", files["shared.txt"], "Shared file must be preserved")
        assertEquals("base content\n", files["base-only.txt"], "Base-only file must be preserved")
        assertEquals("feature content\n", files["feature.txt"], "Feature file must be present in merge")
    }

    @Test
    fun `merge commit has two parents and correct message`() {
        val repo = openRepo()
        val (baseId, featureId) = setupBranches(repo)

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.MERGE_COMMIT, "Merge PR #42", author)
        assertTrue(result.success)

        val mergeCommitId = ObjectId.fromString(result.mergeSha)
        updateRef(repo, "refs/heads/main", mergeCommitId, baseId)

        val freshRepo = openRepo()
        val revWalk = RevWalk(freshRepo)
        val mergeCommit = revWalk.parseCommit(mergeCommitId)
        assertEquals(2, mergeCommit.parentCount, "Merge commit must have two parents")
        assertEquals(baseId, mergeCommit.getParent(0).id, "First parent must be target (main)")
        assertEquals(featureId, mergeCommit.getParent(1).id, "Second parent must be source (feature)")
        assertEquals("Merge PR #42", mergeCommit.fullMessage)
        revWalk.dispose()
    }

    @Test
    fun `merge commit result is accessible from fresh instance after ref update`() {
        val repo = openRepo()
        val (baseId, featureId) = setupBranches(
            repo,
            baseFiles = mapOf("readme.md" to "# Base\n"),
            featureFiles = mapOf("readme.md" to "# Base\n", "new-feature.kt" to "class Feature\n")
        )

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.MERGE_COMMIT, "Merge feature", author)
        assertTrue(result.success)
        val mergeId = ObjectId.fromString(result.mergeSha)
        updateRef(repo, "refs/heads/main", mergeId, baseId)
        repo.close()

        val instance2 = openRepo()
        val files = readAllFiles(instance2, mergeId)
        assertEquals(2, files.size, "Merged tree must have files from both branches")
        assertEquals("# Base\n", files["readme.md"])
        assertEquals("class Feature\n", files["new-feature.kt"])
        instance2.close()

        val instance3 = openRepo()
        val reader = instance3.objectDatabase.newReader()
        assertTrue(reader.has(mergeId), "Merge commit must be readable from instance 3")
        assertTrue(reader.has(baseId), "Base commit must still be accessible")
        assertTrue(reader.has(featureId), "Feature commit must still be accessible")
        reader.close()
        instance3.close()
    }

    // ── Squash strategy ─────────────────────────────────────────────────

    @Test
    fun `squash merge combines content with single parent`() {
        val repo = openRepo()
        val (baseId, featureId) = setupBranches(
            repo,
            baseFiles = mapOf("file.txt" to "base\n"),
            featureFiles = mapOf("file.txt" to "updated\n", "new.txt" to "new content\n")
        )

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.SQUASH, "Squash feature (#1)", author)
        assertTrue(result.success)
        val squashId = ObjectId.fromString(result.mergeSha)
        updateRef(repo, "refs/heads/main", squashId, baseId)

        val freshRepo = openRepo()
        val revWalk = RevWalk(freshRepo)
        val squashCommit = revWalk.parseCommit(squashId)
        assertEquals(1, squashCommit.parentCount, "Squash must have single parent")
        assertEquals(baseId, squashCommit.getParent(0).id, "Parent must be target branch")

        val files = readAllFiles(freshRepo, squashId)
        assertEquals("updated\n", files["file.txt"], "File must have feature branch content")
        assertEquals("new content\n", files["new.txt"], "New file from feature must be present")
        revWalk.dispose()
    }

    // ── Fast-forward strategy ───────────────────────────────────────────

    @Test
    fun `fast-forward merge moves ref without new commit`() {
        val repo = openRepo()
        val (baseId, featureId) = setupBranches(repo)

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.FAST_FORWARD, "FF", author)
        assertTrue(result.success)
        assertEquals(featureId.name(), result.mergeSha, "FF must point to source commit directly")

        updateRef(repo, "refs/heads/main", featureId, baseId)

        val freshRepo = openRepo()
        val files = readAllFiles(freshRepo, featureId)
        assertNotNull(files["feature.txt"], "Feature file must be accessible after FF")
    }

    // ── Rebase strategy ─────────────────────────────────────────────────

    @Test
    fun `rebase replays commits preserving modified content`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("file.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "base"
        }
        val baseId = inserter.insert(baseCommit)

        val modifiedBlob = inserter.insert(Constants.OBJ_BLOB, "modified by feature\n".toByteArray())
        val featureTree = TreeFormatter()
        featureTree.append("file.txt", FileMode.REGULAR_FILE, modifiedBlob)
        val featureTreeId = inserter.insert(featureTree)
        val featureCommit = CommitBuilder().apply {
            setTreeId(featureTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "feature modification"
        }
        val featureId = inserter.insert(featureCommit)
        inserter.flush()

        setRef(repo, "refs/heads/main", baseId)
        setRef(repo, "refs/heads/feature", featureId)

        val result = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.REBASE, "Rebase", author)
        assertTrue(result.success, "Rebase must succeed")
        assertNotNull(result.mergeSha)

        val rebasedId = ObjectId.fromString(result.mergeSha)
        val revWalk = RevWalk(repo)
        val rebasedCommit = revWalk.parseCommit(rebasedId)
        assertEquals(1, rebasedCommit.parentCount, "Rebased commit must have single parent")
        assertEquals(baseId, rebasedCommit.getParent(0).id, "Rebased parent must be target")
        assertEquals("feature modification", rebasedCommit.fullMessage)

        val files = readAllFiles(repo, rebasedId)
        assertEquals("modified by feature\n", files["file.txt"], "Rebased file must have feature content")
        revWalk.dispose()
    }

    @Test
    fun `rebase multi-commit feature preserves all changes`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("file.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "base"
        }
        val baseId = inserter.insert(baseCommit)

        val step1Blob = inserter.insert(Constants.OBJ_BLOB, "step 1\n".toByteArray())
        val step1Tree = TreeFormatter()
        step1Tree.append("file.txt", FileMode.REGULAR_FILE, step1Blob)
        val step1TreeId = inserter.insert(step1Tree)
        val step1Commit = CommitBuilder().apply {
            setTreeId(step1TreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "step 1"
        }
        val step1Id = inserter.insert(step1Commit)

        val step2Blob = inserter.insert(Constants.OBJ_BLOB, "step 2\n".toByteArray())
        val step2Tree = TreeFormatter()
        step2Tree.append("file.txt", FileMode.REGULAR_FILE, step2Blob)
        val step2TreeId = inserter.insert(step2Tree)
        val step2Commit = CommitBuilder().apply {
            setTreeId(step2TreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(step1Id)
            message = "step 2"
        }
        val step2Id = inserter.insert(step2Commit)
        inserter.flush()

        setRef(repo, "refs/heads/main", baseId)
        setRef(repo, "refs/heads/feature", step2Id)

        val result = MergeExecutor.merge(repo, step2Id, baseId, MergeStrategy.REBASE, "Rebase", author)
        assertTrue(result.success)

        val rebasedId = ObjectId.fromString(result.mergeSha)
        val files = readAllFiles(repo, rebasedId)
        assertEquals("step 2\n", files["file.txt"], "Final file must have last feature commit content")

        val revWalk = RevWalk(repo)
        revWalk.markStart(revWalk.parseCommit(rebasedId))
        val history = revWalk.toList()
        assertEquals(3, history.size, "Rebase must preserve all commits (2 replayed + base)")
        assertEquals("step 2", history[0].fullMessage)
        assertEquals("step 1", history[1].fullMessage)
        assertEquals("base", history[2].fullMessage)
        revWalk.dispose()
    }

    // ── Merge conflict detection ────────────────────────────────────────

    @Test
    fun `merge with conflicts reports conflicting files and preserves original branches`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "original\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("conflict.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "base"
        }
        val baseId = inserter.insert(baseCommit)

        val mainBlob = inserter.insert(Constants.OBJ_BLOB, "main version\n".toByteArray())
        val mainTree = TreeFormatter()
        mainTree.append("conflict.txt", FileMode.REGULAR_FILE, mainBlob)
        val mainTreeId = inserter.insert(mainTree)
        val mainCommit = CommitBuilder().apply {
            setTreeId(mainTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "main change"
        }
        val mainId = inserter.insert(mainCommit)

        val featureBlob = inserter.insert(Constants.OBJ_BLOB, "feature version\n".toByteArray())
        val featureTree = TreeFormatter()
        featureTree.append("conflict.txt", FileMode.REGULAR_FILE, featureBlob)
        val featureTreeId = inserter.insert(featureTree)
        val featureCommit = CommitBuilder().apply {
            setTreeId(featureTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "feature change"
        }
        val featureId = inserter.insert(featureCommit)
        inserter.flush()

        setRef(repo, "refs/heads/main", mainId)
        setRef(repo, "refs/heads/feature", featureId)

        val result = MergeExecutor.merge(repo, featureId, mainId, MergeStrategy.MERGE_COMMIT, "Merge", author)
        assertFalse(result.success, "Conflicting merge must fail")

        val freshRepo = openRepo()
        val mainFiles = readAllFiles(freshRepo, mainId)
        assertEquals("main version\n", mainFiles["conflict.txt"], "Main branch must be untouched after failed merge")
        val featureFiles = readAllFiles(freshRepo, featureId)
        assertEquals("feature version\n", featureFiles["conflict.txt"], "Feature branch must be untouched after failed merge")
    }

    // ── Multi-commit feature branch merge ───────────────────────────────

    @Test
    fun `merge preserves multi-commit feature branch history`() {
        val repo = openRepo()
        val inserter = repo.objectDatabase.newInserter()

        val baseBlob = inserter.insert(Constants.OBJ_BLOB, "base\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("file.txt", FileMode.REGULAR_FILE, baseBlob)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "initial"
        }
        val baseId = inserter.insert(baseCommit)

        val feat1Blob = inserter.insert(Constants.OBJ_BLOB, "step 1\n".toByteArray())
        val feat1Tree = TreeFormatter()
        feat1Tree.append("file.txt", FileMode.REGULAR_FILE, feat1Blob)
        feat1Tree.append("step1.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "s1\n".toByteArray()))
        val feat1TreeId = inserter.insert(feat1Tree)
        val feat1Commit = CommitBuilder().apply {
            setTreeId(feat1TreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "feature step 1"
        }
        val feat1Id = inserter.insert(feat1Commit)

        val feat2Blob = inserter.insert(Constants.OBJ_BLOB, "step 2\n".toByteArray())
        val feat2Tree = TreeFormatter()
        feat2Tree.append("file.txt", FileMode.REGULAR_FILE, feat2Blob)
        feat2Tree.append("step1.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "s1\n".toByteArray()))
        feat2Tree.append("step2.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "s2\n".toByteArray()))
        val feat2TreeId = inserter.insert(feat2Tree)
        val feat2Commit = CommitBuilder().apply {
            setTreeId(feat2TreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(feat1Id)
            message = "feature step 2"
        }
        val feat2Id = inserter.insert(feat2Commit)
        inserter.flush()

        setRef(repo, "refs/heads/main", baseId)
        setRef(repo, "refs/heads/feature", feat2Id)

        val result = MergeExecutor.merge(repo, feat2Id, baseId, MergeStrategy.MERGE_COMMIT, "Merge multi-commit feature", author)
        assertTrue(result.success)
        val mergeId = ObjectId.fromString(result.mergeSha)
        updateRef(repo, "refs/heads/main", mergeId, baseId)

        val freshRepo = openRepo()
        val files = readAllFiles(freshRepo, mergeId)
        assertEquals(3, files.size, "All feature files must be in merged result")
        assertEquals("step 2\n", files["file.txt"])
        assertEquals("s1\n", files["step1.txt"])
        assertEquals("s2\n", files["step2.txt"])

        val revWalk = RevWalk(freshRepo)
        revWalk.markStart(revWalk.parseCommit(mergeId))
        val history = mutableListOf<String>()
        for (c in revWalk) { history.add(c.fullMessage) }
        assertTrue(history.contains("feature step 1"), "Feature commit 1 must be in merge history")
        assertTrue(history.contains("feature step 2"), "Feature commit 2 must be in merge history")
        assertTrue(history.contains("initial"), "Base commit must be in merge history")
        revWalk.dispose()
    }

    // ── Merge then push verifies full round-trip ────────────────────────

    @Test
    fun `merge result pushed to another branch is fetchable`() {
        val repo = openRepo()
        val (baseId, featureId) = setupBranches(
            repo,
            baseFiles = mapOf("base.txt" to "base\n"),
            featureFiles = mapOf("base.txt" to "base\n", "feature.txt" to "feature\n")
        )

        val mergeResult = MergeExecutor.merge(repo, featureId, baseId, MergeStrategy.MERGE_COMMIT, "Merge", author)
        assertTrue(mergeResult.success)
        val mergeId = ObjectId.fromString(mergeResult.mergeSha)
        updateRef(repo, "refs/heads/main", mergeId, baseId)

        setRef(repo, "refs/heads/release", mergeId)

        val freshRepo = openRepo()
        val releaseRef = freshRepo.refDatabase.findRef("refs/heads/release")
        assertNotNull(releaseRef, "Release branch must exist")
        assertEquals(mergeId, releaseRef.objectId)

        val files = readAllFiles(freshRepo, releaseRef.objectId)
        assertEquals("base\n", files["base.txt"])
        assertEquals("feature\n", files["feature.txt"])
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun setupBranches(
        repo: BoscaDfsRepository,
        baseFiles: Map<String, String> = mapOf("base.txt" to "base content\n"),
        featureFiles: Map<String, String> = mapOf("base.txt" to "base content\n", "feature.txt" to "feature content\n")
    ): Pair<ObjectId, ObjectId> {
        val inserter = repo.objectDatabase.newInserter()

        val baseTreeId = buildTree(inserter, baseFiles.mapValues { (_, v) ->
            inserter.insert(Constants.OBJ_BLOB, v.toByteArray())
        })
        val baseCommit = CommitBuilder().apply {
            setTreeId(baseTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            message = "base commit"
        }
        val baseId = inserter.insert(baseCommit)

        val featureTreeId = buildTree(inserter, featureFiles.mapValues { (_, v) ->
            inserter.insert(Constants.OBJ_BLOB, v.toByteArray())
        })
        val featureCommit = CommitBuilder().apply {
            setTreeId(featureTreeId)
            this.author = this@EndToEndMergeIntegrationTest.author
            this.committer = this@EndToEndMergeIntegrationTest.author
            setParentId(baseId)
            message = "feature commit"
        }
        val featureId = inserter.insert(featureCommit)
        inserter.flush()

        setRef(repo, "refs/heads/main", baseId)
        setRef(repo, "refs/heads/feature", featureId)

        return baseId to featureId
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

    private fun updateRef(repo: BoscaDfsRepository, refName: String, newId: ObjectId, expectedOldId: ObjectId) {
        val update = repo.refDatabase.newUpdate(refName, false)
        update.setNewObjectId(newId)
        update.setExpectedOldObjectId(expectedOldId)
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
