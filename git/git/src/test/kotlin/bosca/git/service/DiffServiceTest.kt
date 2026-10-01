package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.DiffChangeType
import bosca.serialization.UUID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffServiceTest {

    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val service = DiffServiceImpl(dfsManager)
    private val repositoryId = UUID.random()
    private lateinit var inMemoryRepo: InMemoryRepository
    private var baseCommitId: ObjectId = ObjectId.zeroId()
    private var headCommitId: ObjectId = ObjectId.zeroId()

    @BeforeTest
    fun setup() {
        inMemoryRepo = InMemoryRepository(DfsRepositoryDescription("test"))
        createTestContent()
        every { dfsManager.open(repositoryId) } returns inMemoryRepo
    }

    private fun createTestContent() {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val author = PersonIdent("Test", "test@example.com")

        val fileA = inserter.insert(Constants.OBJ_BLOB, "line 1\nline 2\nline 3\n".toByteArray())
        val tree1 = TreeFormatter()
        tree1.append("file.txt", FileMode.REGULAR_FILE, fileA)
        val treeId1 = inserter.insert(tree1)
        val commit1 = CommitBuilder()
        commit1.setTreeId(treeId1)
        commit1.setAuthor(author)
        commit1.setCommitter(author)
        commit1.setMessage("base commit")
        baseCommitId = inserter.insert(commit1)

        val fileB = inserter.insert(Constants.OBJ_BLOB, "line 1\nline 2 modified\nline 3\nline 4\n".toByteArray())
        val newFile = inserter.insert(Constants.OBJ_BLOB, "new content\n".toByteArray())
        val tree2 = TreeFormatter()
        tree2.append("file.txt", FileMode.REGULAR_FILE, fileB)
        tree2.append("new.txt", FileMode.REGULAR_FILE, newFile)
        val treeId2 = inserter.insert(tree2)
        val commit2 = CommitBuilder()
        commit2.setTreeId(treeId2)
        commit2.setAuthor(author)
        commit2.setCommitter(author)
        commit2.setMessage("modify and add")
        commit2.setParentId(baseCommitId)
        headCommitId = inserter.insert(commit2)

        inserter.flush()

        val mainRef = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(baseCommitId)
        mainRef.update()
        val featureRef = inMemoryRepo.refDatabase.newUpdate("refs/heads/feature", true)
        featureRef.setNewObjectId(headCommitId)
        featureRef.update()
    }

    @Test
    fun `computeDiff detects modified file`() = runTest {
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/feature")
        val modified = diff.find { it.newPath == "file.txt" }
        assertEquals(DiffChangeType.MODIFY, modified?.changeType)
    }

    @Test
    fun `computeDiff detects added file`() = runTest {
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/feature")
        val added = diff.find { it.newPath == "new.txt" }
        assertEquals(DiffChangeType.ADD, added?.changeType)
    }

    @Test
    fun `computeDiff returns hunks with line content`() = runTest {
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/feature")
        val modified = diff.find { it.newPath == "file.txt" }!!
        assertTrue(modified.hunks.isNotEmpty())
        val allLines = modified.hunks.flatMap { it.lines }
        assertTrue(allLines.isNotEmpty())
        val addedContent = allLines.filter { it.type == bosca.git.model.DiffLineType.ADD }.map { it.content }
        assertTrue(addedContent.any { it.isNotEmpty() }, "Added lines should have content")
    }

    @Test
    fun `computeDiff between identical refs returns empty`() = runTest {
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/main")
        assertTrue(diff.isEmpty())
    }

    @Test
    fun `computeDiff uses merge-base not target tip`() = runTest {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val author = PersonIdent("Test", "test@example.com")

        // Advance main with an unrelated change (add unrelated.txt)
        val unrelatedBlob = inserter.insert(Constants.OBJ_BLOB, "unrelated\n".toByteArray())
        val fileA = inserter.insert(Constants.OBJ_BLOB, "line 1\nline 2\nline 3\n".toByteArray())
        val tree3 = TreeFormatter()
        tree3.append("file.txt", FileMode.REGULAR_FILE, fileA)
        tree3.append("unrelated.txt", FileMode.REGULAR_FILE, unrelatedBlob)
        val treeId3 = inserter.insert(tree3)
        val commit3 = CommitBuilder()
        commit3.setTreeId(treeId3)
        commit3.setAuthor(author)
        commit3.setCommitter(author)
        commit3.setMessage("advance main")
        commit3.setParentId(baseCommitId)
        val advancedMainId = inserter.insert(commit3)
        inserter.flush()

        val mainRef = inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(advancedMainId)
        mainRef.isForceUpdate = true
        mainRef.update()

        // PR diff (main...feature) should only show feature's changes,
        // not a deletion of unrelated.txt
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/feature")
        val paths = diff.map { it.newPath ?: it.oldPath }
        assertTrue(paths.contains("file.txt"), "Should contain modified file")
        assertTrue(paths.contains("new.txt"), "Should contain added file")
        assertTrue(!paths.any { it == "unrelated.txt" }, "Should not show unrelated.txt from target branch")
    }

    @Test
    fun `computeDiff renders gitlink changes without reading their commits as blobs`() = runTest {
        val oldGitlink = ObjectId.fromString("76f4a1a04790d8cfd12b674330eb582b3536c5e8")
        val newGitlink = ObjectId.fromString("88329f08f885990cbc4753276c87184c249cf300")
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        val baseTree = TreeFormatter().apply { append("ai", FileMode.GITLINK, oldGitlink) }
        val base = CommitBuilder().apply {
            setTreeId(inserter.insert(baseTree))
            setAuthor(person)
            setCommitter(person)
            setMessage("pin old submodule")
        }
        val baseId = inserter.insert(base)
        val headTree = TreeFormatter().apply { append("ai", FileMode.GITLINK, newGitlink) }
        val head = CommitBuilder().apply {
            setTreeId(inserter.insert(headTree))
            setAuthor(person)
            setCommitter(person)
            setMessage("advance submodule")
            setParentId(baseId)
        }
        val headId = inserter.insert(head)
        inserter.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/gitlink-base", true).apply {
            setNewObjectId(baseId)
            update()
        }
        inMemoryRepo.refDatabase.newUpdate("refs/heads/gitlink-head", true).apply {
            setNewObjectId(headId)
            update()
        }

        val file = service.computeDiff(
            repositoryId,
            "refs/heads/gitlink-base",
            "refs/heads/gitlink-head"
        ).single()

        assertEquals("ai", file.oldPath)
        assertEquals("ai", file.newPath)
        assertEquals(DiffChangeType.MODIFY, file.changeType)
        val hunk = file.hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(1, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(1, hunk.newCount)
        assertEquals(2, hunk.lines.size)
        assertEquals("Subproject commit ${oldGitlink.name()}", hunk.lines[0].content)
        assertEquals(1, hunk.lines[0].oldLineNumber)
        assertNull(hunk.lines[0].newLineNumber)
        assertEquals("Subproject commit ${newGitlink.name()}", hunk.lines[1].content)
        assertNull(hunk.lines[1].oldLineNumber)
        assertEquals(1, hunk.lines[1].newLineNumber)
    }

    // ── appended coverage: delete arm, unknown refs, orphan histories ────

    @Test
    fun `computeDiff detects a deleted file`() = runTest {
        // A third commit on top of head that removes new.txt.
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        val fileB = inserter.insert(Constants.OBJ_BLOB, "line 1\nline 2 modified\nline 3\nline 4\n".toByteArray())
        val tree = TreeFormatter().apply { append("file.txt", FileMode.REGULAR_FILE, fileB) }
        val commit = CommitBuilder().apply {
            setTreeId(inserter.insert(tree)); setAuthor(person); setCommitter(person)
            setMessage("delete new.txt"); setParentId(headCommitId)
        }
        val deleteCommit = inserter.insert(commit)
        inserter.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/delete", true).apply {
            setNewObjectId(deleteCommit); update()
        }

        val files = service.computeDiff(repositoryId, "refs/heads/feature", "refs/heads/delete")

        val deleted = files.single { it.changeType == bosca.git.model.DiffChangeType.DELETE }
        assertEquals("new.txt", deleted.oldPath)
        assertEquals(null, deleted.newPath)
        // Every line of the deleted file appears as a DELETE line built from old text.
        assertTrue(deleted.hunks.flatMap { it.lines }.all { it.type == bosca.git.model.DiffLineType.DELETE })
    }

    @Test
    fun `computeDiff throws for unknown refs`() = runTest {
        try { service.computeDiff(repositoryId, "refs/heads/nope", "refs/heads/feature"); kotlin.test.fail() }
        catch (_: NoSuchElementException) { /* expected */ }
        try { service.computeDiff(repositoryId, "refs/heads/feature", "refs/heads/nope"); kotlin.test.fail() }
        catch (_: NoSuchElementException) { /* expected */ }
    }

    @Test
    fun `computeDiff refuses unrelated histories with no merge base`() = runTest {
        val inserter = inMemoryRepo.objectDatabase.newInserter()
        val person = PersonIdent("Test", "test@example.com")
        val blob = inserter.insert(Constants.OBJ_BLOB, "orphan\n".toByteArray())
        val tree = TreeFormatter().apply { append("orphan.txt", FileMode.REGULAR_FILE, blob) }
        val commit = CommitBuilder().apply {
            setTreeId(inserter.insert(tree)); setAuthor(person); setCommitter(person); setMessage("orphan root")
        }
        val orphan = inserter.insert(commit)
        inserter.flush()
        inMemoryRepo.refDatabase.newUpdate("refs/heads/orphan", true).apply {
            setNewObjectId(orphan); update()
        }

        // Unrelated histories have no merge base — the diff is refused.
        try {
            service.computeDiff(repositoryId, "refs/heads/orphan", "refs/heads/feature")
            kotlin.test.fail("expected NoSuchElementException")
        } catch (_: NoSuchElementException) { /* expected */ }
    }
}
