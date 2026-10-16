package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.DiffChangeType
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
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
import kotlin.test.assertFailsWith

class DiffServiceTest {

    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val service = DiffServiceImpl(dfsManager)
    private val repositoryId = UUID.random()
    private lateinit var inMemoryRepo: InMemoryRepository
    private var baseCommitId: ObjectId = ObjectId.zeroId()
    private var headCommitId: ObjectId = ObjectId.zeroId()

    private fun mergedPull(strategy: MergeStrategy, sha: String, base: String? = null) = PullRequest(
        repositoryId = repositoryId, title = "Merged changes", authorId = UUID.random(),
        sourceBranch = "feature", targetBranch = "main", status = PullRequestStatus.MERGED,
        mergeStrategy = strategy, mergeSha = sha, mergeBaseSha = base,
    )

    @Test
    fun `merged comparisons retain all source commits for every strategy after branch changes`() = runTest {
        val author = PersonIdent("Merger", "merger@example.test")
        val second = inMemoryRepo.objectDatabase.newInserter().use { inserter ->
            val tree = TreeFormatter().apply {
                append("file.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "line 1\nline 2 modified\nline 3\nline 4\n".toByteArray()))
                append("new.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "new content\n".toByteArray()))
                append("second.txt", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, "Second source commit\n".toByteArray()))
            }
            val commit = CommitBuilder().apply {
                setTreeId(inserter.insert(tree)); setParentId(headCommitId)
                setAuthor(author); setCommitter(author); setMessage("Second source commit")
            }
            inserter.insert(commit).also { inserter.flush() }
        }
        val originalDiff = service.computeDiff(repositoryId, baseCommitId.name(), second.name())
        assertEquals(setOf("file.txt", "new.txt", "second.txt"), originalDiff.map { it.newPath }.toSet())
        for (strategy in MergeStrategy.entries) {
            val result = MergeExecutor.merge(inMemoryRepo, second, baseCommitId, strategy, "Merge source", author)
            assertTrue(result.success, strategy.name)
            val sha = kotlin.test.assertNotNull(result.mergeSha)
            inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true).apply {
                setNewObjectId(ObjectId.fromString(sha)); setForceUpdate(true); update()
            }
            val pr = mergedPull(strategy, sha, baseCommitId.name())
            assertEquals(baseCommitId.name(), service.resolveMergedBase(pr))
            if (strategy == MergeStrategy.MERGE_COMMIT)
                assertEquals(baseCommitId.name(), service.resolveMergedBase(pr.copy(mergeStrategy = null)))
            assertEquals(originalDiff, service.computeDiff(repositoryId, pr.mergeBaseSha.orEmpty(), sha), strategy.name)
            inMemoryRepo.refDatabase.newUpdate("refs/heads/feature", true).apply { setForceUpdate(true); delete() }
            inMemoryRepo.refDatabase.newUpdate("refs/heads/main", true).apply {
                setNewObjectId(headCommitId); setForceUpdate(true); update()
            }
            assertEquals(originalDiff, service.computeDiff(repositoryId, pr.mergeBaseSha.orEmpty(), sha), strategy.name)
            val commits = service.listComparisonCommits(repositoryId, pr.mergeBaseSha.orEmpty(), sha, 0, 100)
            assertTrue(commits.isNotEmpty(), strategy.name)
            if (strategy == MergeStrategy.SQUASH) assertEquals(1, commits.size)
            for (commit in commits) {
                assertTrue(service.computeCommitDiff(repositoryId, pr.mergeBaseSha.orEmpty(), sha, commit.sha).isNotEmpty(), strategy.name)
            }
        }
    }

    @Test
    fun `merge and squash parents recover existing comparisons without recorded bases`() = runTest {
        for (strategy in listOf(MergeStrategy.MERGE_COMMIT, MergeStrategy.SQUASH)) {
            val result = MergeExecutor.merge(inMemoryRepo, headCommitId, baseCommitId, strategy, "Merge", PersonIdent("Test", "test@example.com"))
            val pr = mergedPull(strategy, kotlin.test.assertNotNull(result.mergeSha))
            assertEquals(baseCommitId.name(), service.resolveMergedBase(pr))
            assertEquals(baseCommitId.name(), service.resolveMergedBase(pr.copy(mergeBaseSha = " ")))
            if (strategy == MergeStrategy.MERGE_COMMIT)
                assertEquals(baseCommitId.name(), service.resolveMergedBase(pr.copy(mergeStrategy = null)))
            assertEquals(service.computeDiff(repositoryId, baseCommitId.name(), headCommitId.name()),
                service.computeDiff(repositoryId, service.resolveMergedBase(pr).orEmpty(), pr.mergeSha.orEmpty()))
        }
    }

    @Test
    fun `missing merged metadata is explicit and unknown multi-commit bases are not guessed`() = runTest {
        val pr = mergedPull(MergeStrategy.MERGE_COMMIT, headCommitId.name())
        assertNull(service.resolveMergedBase(pr.copy(status = PullRequestStatus.OPEN)))
        assertNull(service.resolveMergedBase(pr.copy(mergeSha = null)))
        assertNull(service.resolveMergedBase(pr.copy(mergeSha = " ")))
        for (strategy in listOf(null, MergeStrategy.REBASE, MergeStrategy.FAST_FORWARD)) {
            assertNull(service.resolveMergedBase(pr.copy(mergeStrategy = strategy)))
        }
        assertNull(service.resolveMergedBase(pr.copy(mergeSha = baseCommitId.name())))
        assertNull(service.resolveMergedBase(pr.copy(mergeStrategy = MergeStrategy.SQUASH, mergeSha = baseCommitId.name())))
        assertFailsWith<org.eclipse.jgit.errors.MissingObjectException> {
            service.resolveMergedBase(pr.copy(mergeSha = ObjectId.zeroId().name()))
        }
        every { dfsManager.open(repositoryId) } throws kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> { service.resolveMergedBase(pr) }
    }

    private fun commitWithFiles(message: String, files: Map<String, String>, vararg parents: ObjectId): ObjectId =
        inMemoryRepo.newObjectInserter().use { inserter ->
            val tree = TreeFormatter()
            files.toSortedMap().forEach { (path, content) ->
                tree.append(path, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
            }
            val author = PersonIdent("Author", "author@example.test")
            inserter.insert(CommitBuilder().apply {
                setTreeId(inserter.insert(tree)); setParentIds(*parents)
                setAuthor(author); setCommitter(author); setMessage(message)
            }).also { inserter.flush() }
        }

    @Test
    fun `comparison commits paginate beyond 250 and exclude target history`() = runTest {
        var head = baseCommitId
        val history = (1..270).map { index ->
            commitWithFiles("Change $index", mapOf("file.txt" to "$index\n"), head).also { head = it }
        }
        val all = (0L..275L step 25).flatMap { offset ->
            service.listComparisonCommits(repositoryId, baseCommitId.name(), head.name(), offset, 25)
        }
        assertEquals(history.reversed().map { it.name() }, all.map { it.sha })
        assertEquals("Change 270", all.first().message)
        assertEquals("Author", all.first().authorName)
        assertEquals(listOf(history[268].name()), all.first().parentShas)
        assertTrue(service.listComparisonCommits(repositoryId, head.name(), head.name(), 0, 25).isEmpty())
        assertTrue(service.listComparisonCommits(repositoryId, baseCommitId.name(), head.name(), Long.MAX_VALUE, 25).isEmpty())
        for ((offset, limit) in listOf(-1L to 25, 0L to 0, 0L to 101)) {
            assertFailsWith<IllegalArgumentException> {
                service.listComparisonCommits(repositoryId, baseCommitId.name(), head.name(), offset, limit)
            }
        }
    }

    @Test
    fun `commit diff contains only selected changes and rejects commits outside the PR`() = runTest {
        val second = commitWithFiles("Second", mapOf("file.txt" to "line 1\nline 2 modified\nline 3\nline 4\n",
            "new.txt" to "new content\n", "second.txt" to "second\n"), headCommitId)
        val diff = service.computeCommitDiff(repositoryId, baseCommitId.name(), second.name(), second.name())
        assertEquals(listOf("second.txt"), diff.map { it.newPath })
        assertEquals(service.computeDiff(repositoryId, baseCommitId.name(), headCommitId.name()),
            service.computeCommitDiff(repositoryId, baseCommitId.name(), second.name(), headCommitId.name()))
        val unrelated = commitWithFiles("Unrelated", mapOf("unrelated.txt" to "other"), baseCommitId)
        for (sha in listOf(baseCommitId.name(), unrelated.name(), "feature", ObjectId.zeroId().name())) {
            assertFailsWith<IllegalArgumentException> {
                service.computeCommitDiff(repositoryId, baseCommitId.name(), second.name(), sha)
            }
        }
        for ((base, head) in listOf("missing" to second.name(), baseCommitId.name() to "missing")) {
            assertFailsWith<NoSuchElementException> {
                service.listComparisonCommits(repositoryId, base, head, 0, 25)
            }
        }
    }

    @Test
    fun `merge commits compare to first parent and root commits to empty tree`() = runTest {
        val merge = commitWithFiles("Merge", mapOf("file.txt" to "line 1\nline 2\nline 3\n", "merged.txt" to "resolved"), baseCommitId, headCommitId)
        assertEquals(listOf("merged.txt"), service.computeCommitDiff(repositoryId, baseCommitId.name(), merge.name(), merge.name()).map { it.newPath })
        val root = commitWithFiles("Root", mapOf("root.txt" to "root\n"))
        val rootDiff = service.computeCommitDiff(repositoryId, baseCommitId.name(), root.name(), root.name())
        assertEquals(listOf("root.txt"), rootDiff.map { it.newPath })
        assertEquals(DiffChangeType.ADD, rootDiff.single().changeType)
        assertEquals(1, rootDiff.single().newLineCount)
        every { dfsManager.open(repositoryId) } throws kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.computeCommitDiff(repositoryId, baseCommitId.name(), root.name(), root.name())
        }
    }

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

    private fun commitFiles(parent: ObjectId?, ref: String, vararg files: Pair<String, ByteArray>): ObjectId {
        val person = PersonIdent("Test", "test@example.com")
        return inMemoryRepo.objectDatabase.newInserter().use { inserter ->
            val tree = TreeFormatter().apply {
                for ((name, bytes) in files.sortedBy { it.first }) {
                    append(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, bytes))
                }
            }
            val commit = CommitBuilder().apply {
                setTreeId(inserter.insert(tree)); setAuthor(person); setCommitter(person); setMessage(ref)
                parent?.let { setParentId(it) }
            }
            inserter.insert(commit).also { id ->
                inserter.flush()
                inMemoryRepo.refDatabase.newUpdate("refs/heads/$ref", true).apply {
                    setNewObjectId(id); setForceUpdate(true); update()
                }
            }
        }
    }

    private fun numbered(count: Int, change: (Int) -> String? = { "line $it" }): ByteArray =
        (1..count).mapNotNull(change).joinToString("") { "$it\n" }.toByteArray()

    @Test
    fun `computeDiff windows distant changes into separate hunks with three lines of context`() = runTest {
        val base = commitFiles(null, "window-base", "long.txt" to numbered(40))
        commitFiles(base, "window-head", "long.txt" to numbered(40) { if (it == 5 || it == 30) "changed $it" else "line $it" })

        val file = service.computeDiff(repositoryId, "refs/heads/window-base", "refs/heads/window-head").single()

        assertEquals(40, file.newLineCount)
        assertEquals(2, file.hunks.size)
        val (first, second) = file.hunks
        assertEquals(listOf(2, 7, 2, 7), listOf(first.oldStart, first.oldCount, first.newStart, first.newCount))
        assertEquals(listOf(27, 7, 27, 7), listOf(second.oldStart, second.oldCount, second.newStart, second.newCount))
        assertEquals(
            listOf("CONTEXT 2 2", "CONTEXT 3 3", "CONTEXT 4 4", "DELETE 5 null", "ADD null 5", "CONTEXT 6 6", "CONTEXT 7 7", "CONTEXT 8 8"),
            first.lines.map { "${it.type} ${it.oldLineNumber} ${it.newLineNumber}" },
        )
        assertEquals("line 2", first.lines.first().content)
        assertEquals("changed 5", first.lines[4].content)
        assertEquals("line 33", second.lines.last().content)
        assertTrue(file.hunks.flatMap { it.lines }.none { it.newLineNumber in 9..26 }, "Unchanged lines between hunks stay hidden")
    }

    @Test
    fun `computeDiff merges nearby changes and clips context at file boundaries`() = runTest {
        val base = commitFiles(null, "merge-base", "short.txt" to numbered(12))
        commitFiles(base, "merge-head", "short.txt" to numbered(13) {
            when (it) {
                1 -> "first changed"
                8 -> null
                else -> "line $it"
            }
        } + "appended\n".toByteArray())

        val file = service.computeDiff(repositoryId, "refs/heads/merge-base", "refs/heads/merge-head").single()

        val hunk = file.hunks.single()
        assertEquals(listOf(1, 12, 1, 13), listOf(hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount))
        assertEquals("DELETE", hunk.lines.first().type.name)
        assertEquals("appended", hunk.lines.last().content)
        assertEquals(13, file.newLineCount)
        assertEquals(hunk.lines.count { it.type != bosca.git.model.DiffLineType.ADD }, hunk.oldCount)
        assertEquals(hunk.lines.count { it.type != bosca.git.model.DiffLineType.DELETE }, hunk.newCount)
    }

    @Test
    fun `computeDiff keeps empty text changes distinct from binary files`() = runTest {
        val binary = byteArrayOf(0, 1, 2, 3, 0, 5)
        val base = commitFiles(null, "kinds-base", "image.bin" to binary, "same.txt" to numbered(3))
        commitFiles(
            base, "kinds-head",
            "empty.txt" to ByteArray(0),
            "image.bin" to byteArrayOf(0, 9, 9, 9, 0, 5),
            "same.txt" to numbered(3),
        )

        val files = service.computeDiff(repositoryId, "refs/heads/kinds-base", "refs/heads/kinds-head").associateBy { it.newPath }

        val empty = files.getValue("empty.txt")
        assertEquals(listOf(bosca.git.model.DiffHunk(0, 0, 0, 0, emptyList())), empty.hunks)
        assertEquals(0, empty.newLineCount)
        val image = files.getValue("image.bin")
        assertTrue(image.hunks.isEmpty())
        assertEquals(0, image.newLineCount)
        assertNull(files["same.txt"])
    }

    @Test
    fun `computeDiff numbers added files from the first line`() = runTest {
        val diff = service.computeDiff(repositoryId, "refs/heads/main", "refs/heads/feature")
        val added = diff.single { it.newPath == "new.txt" }.hunks.single()
        assertEquals(listOf(0, 0, 1, 1), listOf(added.oldStart, added.oldCount, added.newStart, added.newCount))
        val modified = diff.single { it.newPath == "file.txt" }
        assertEquals(4, modified.newLineCount)
        assertEquals(
            listOf("CONTEXT 1 1", "DELETE 2 null", "ADD null 2", "CONTEXT 3 3", "ADD null 4"),
            modified.hunks.single().lines.map { "${it.type} ${it.oldLineNumber} ${it.newLineNumber}" },
        )
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
