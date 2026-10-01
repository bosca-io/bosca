package bosca.git.service

import bosca.git.model.MergeStrategy
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MergeExecutorTest {

    private lateinit var repo: InMemoryRepository
    private val author = PersonIdent("Test", "test@example.com")
    private var baseCommitId: ObjectId = ObjectId.zeroId()
    private var sourceCommitId: ObjectId = ObjectId.zeroId()

    @BeforeTest
    fun setup() {
        repo = InMemoryRepository(DfsRepositoryDescription("test"))
        createBranches()
    }

    private fun createBranches() {
        val inserter = repo.objectDatabase.newInserter()

        val baseFile = inserter.insert(Constants.OBJ_BLOB, "base content\n".toByteArray())
        val baseTree = TreeFormatter()
        baseTree.append("file.txt", FileMode.REGULAR_FILE, baseFile)
        val baseTreeId = inserter.insert(baseTree)
        val baseCommit = CommitBuilder()
        baseCommit.setTreeId(baseTreeId)
        baseCommit.setAuthor(author)
        baseCommit.setCommitter(author)
        baseCommit.setMessage("base commit")
        baseCommitId = inserter.insert(baseCommit)

        val sourceFile = inserter.insert(Constants.OBJ_BLOB, "source content\n".toByteArray())
        val sourceTree = TreeFormatter()
        sourceTree.append("file.txt", FileMode.REGULAR_FILE, sourceFile)
        val sourceTreeId = inserter.insert(sourceTree)
        val sourceCommit = CommitBuilder()
        sourceCommit.setTreeId(sourceTreeId)
        sourceCommit.setAuthor(author)
        sourceCommit.setCommitter(author)
        sourceCommit.setMessage("source commit")
        sourceCommit.setParentId(baseCommitId)
        sourceCommitId = inserter.insert(sourceCommit)

        inserter.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(baseCommitId)
        mainRef.update()
        val featureRef = repo.refDatabase.newUpdate("refs/heads/feature", true)
        featureRef.setNewObjectId(sourceCommitId)
        featureRef.update()
    }

    @Test
    fun `merge commit strategy creates merge commit with two parents`() {
        val result = MergeExecutor.merge(
            repo, sourceCommitId, baseCommitId,
            MergeStrategy.MERGE_COMMIT, "Merge feature", author
        )
        assertTrue(result.success)
        assertNotNull(result.mergeSha)

        val revWalk = RevWalk(repo)
        val mergeCommit = revWalk.parseCommit(ObjectId.fromString(result.mergeSha))
        assertEquals(2, mergeCommit.parentCount)
        revWalk.dispose()
    }

    @Test
    fun `squash strategy creates single commit`() {
        val result = MergeExecutor.merge(
            repo, sourceCommitId, baseCommitId,
            MergeStrategy.SQUASH, "Squash feature (#1)", author
        )
        assertTrue(result.success)
        assertNotNull(result.mergeSha)

        val revWalk = RevWalk(repo)
        val squashCommit = revWalk.parseCommit(ObjectId.fromString(result.mergeSha))
        assertEquals(1, squashCommit.parentCount)
        assertEquals(baseCommitId, squashCommit.getParent(0).id)
        revWalk.dispose()
    }

    @Test
    fun `fast forward strategy moves target to source HEAD`() {
        val result = MergeExecutor.merge(
            repo, sourceCommitId, baseCommitId,
            MergeStrategy.FAST_FORWARD, "Fast forward", author
        )
        assertTrue(result.success)
        assertEquals(sourceCommitId.name(), result.mergeSha)
    }

    @Test
    fun `rebase strategy replays commits on target`() {
        val result = MergeExecutor.merge(
            repo, sourceCommitId, baseCommitId,
            MergeStrategy.REBASE, "Rebase feature", author
        )
        assertTrue(result.success)
        assertNotNull(result.mergeSha)

        val revWalk = RevWalk(repo)
        val rebasedCommit = revWalk.parseCommit(ObjectId.fromString(result.mergeSha))
        assertEquals(1, rebasedCommit.parentCount)
        revWalk.dispose()
    }

    @Test
    fun `checkMergeability returns success for mergeable branches`() {
        val result = MergeExecutor.checkMergeability(repo, sourceCommitId, baseCommitId)
        assertTrue(result.success)
    }

    @Test
    fun `merge with conflicts reports conflicting files`() {
        val inserter = repo.objectDatabase.newInserter()

        val conflictFile = inserter.insert(Constants.OBJ_BLOB, "conflicting content\n".toByteArray())
        val conflictTree = TreeFormatter()
        conflictTree.append("file.txt", FileMode.REGULAR_FILE, conflictFile)
        val conflictTreeId = inserter.insert(conflictTree)
        val conflictCommit = CommitBuilder()
        conflictCommit.setTreeId(conflictTreeId)
        conflictCommit.setAuthor(author)
        conflictCommit.setCommitter(author)
        conflictCommit.setMessage("conflicting commit on main")
        conflictCommit.setParentId(baseCommitId)
        val conflictCommitId = inserter.insert(conflictCommit)
        inserter.flush()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", false)
        mainRef.setNewObjectId(conflictCommitId)
        mainRef.setForceUpdate(true)
        mainRef.update()

        val result = MergeExecutor.checkMergeability(repo, sourceCommitId, conflictCommitId)
        assertFalse(result.success)
    }

    // ── appended coverage: conflict and impossibility arms ───────────────

    /** Advances main to a commit that conflicts with the feature branch. */
    private fun divergeMain(): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, "conflicting content\n".toByteArray())
        val tree = TreeFormatter().apply { append("file.txt", FileMode.REGULAR_FILE, blob) }
        val treeId = inserter.insert(tree)
        val person = author
        val commit = CommitBuilder().apply {
            setTreeId(treeId); setAuthor(person); setCommitter(person)
            setMessage("conflicting commit on main"); setParentId(baseCommitId)
        }
        val id = inserter.insert(commit)
        inserter.flush()
        repo.refDatabase.newUpdate("refs/heads/main", false).apply {
            setNewObjectId(id); isForceUpdate = true; update()
        }
        return id
    }

    @Test
    fun `merge commit strategy reports conflicting files on conflict`() {
        val mainHead = divergeMain()
        val result = MergeExecutor.merge(repo, sourceCommitId, mainHead, MergeStrategy.MERGE_COMMIT, "m", author)
        assertFalse(result.success)
        assertTrue(result.conflictingFiles.contains("file.txt"))
    }

    @Test
    fun `squash strategy reports conflicting files on conflict`() {
        val mainHead = divergeMain()
        val result = MergeExecutor.merge(repo, sourceCommitId, mainHead, MergeStrategy.SQUASH, "m", author)
        assertFalse(result.success)
        assertTrue(result.conflictingFiles.contains("file.txt"))
    }

    @Test
    fun `fast forward fails when the target has diverged`() {
        val mainHead = divergeMain()
        val result = MergeExecutor.merge(repo, sourceCommitId, mainHead, MergeStrategy.FAST_FORWARD, "m", author)
        assertFalse(result.success)
    }

    @Test
    fun `rebase fails on per-commit conflicts`() {
        val mainHead = divergeMain()
        val result = MergeExecutor.merge(repo, sourceCommitId, mainHead, MergeStrategy.REBASE, "m", author)
        assertFalse(result.success)
    }

    @Test
    fun `rebase fails without a common merge base`() {
        // An unrelated root commit shares no history with feature.
        val inserter = repo.objectDatabase.newInserter()
        val blob = inserter.insert(Constants.OBJ_BLOB, "orphan\n".toByteArray())
        val tree = TreeFormatter().apply { append("other.txt", FileMode.REGULAR_FILE, blob) }
        val person = author
        val commit = CommitBuilder().apply {
            setTreeId(inserter.insert(tree)); setAuthor(person); setCommitter(person); setMessage("orphan root")
        }
        val orphan = inserter.insert(commit)
        inserter.flush()

        val result = MergeExecutor.merge(repo, sourceCommitId, orphan, MergeStrategy.REBASE, "m", author)
        assertFalse(result.success)
    }
}
