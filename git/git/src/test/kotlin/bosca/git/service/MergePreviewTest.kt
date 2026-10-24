package bosca.git.service

import bosca.git.TestDfsRefAdapter
import bosca.git.TestDfsStorageAdapter
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.dfs.DfsPackInfo
import bosca.git.dfs.DfsStorageAdapter
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real JGit merges through Bosca DFS with a storage boundary that rejects every preview write. */
class MergePreviewTest {
    private val repositoryId = UUID.random()
    private val storage = TestDfsStorageAdapter()
    private val refs = TestDfsRefAdapter()
    private val author = PersonIdent("Test", "test@example.com")

    private fun repository(readOnly: Boolean = false): DfsRepository = BoscaDfsRepositoryBuilder().apply {
        repositoryId = this@MergePreviewTest.repositoryId
        repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        refAdapter = refs
        storageAdapter = if (!readOnly) storage else object : DfsStorageAdapter by storage {
            override fun createPack(repositoryId: UUID, packName: String, source: String): DfsPackInfo =
                error("Merge previews must not create packs")

            override fun writeFile(packInfo: DfsPackInfo, extension: String, data: ByteArray): Unit =
                error("Merge previews must not write objects")

            override fun commitPacks(toCommit: List<DfsPackInfo>, toReplace: List<DfsPackInfo>): Unit =
                error("Merge previews must not commit packs")

            override fun rollbackPacks(packs: List<DfsPackInfo>): Unit =
                error("Merge previews must not need storage rollback")
        }
    }.build()

    private fun commit(files: Map<String, String>, vararg parents: ObjectId): ObjectId = repository().use { repo ->
        repo.newObjectInserter().use { inserter ->
            val tree = TreeFormatter()
            for ((path, content) in files.toSortedMap()) {
                tree.append(path, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
            }
            val id = inserter.insert(CommitBuilder().apply {
                setTreeId(inserter.insert(tree))
                setParentIds(*parents)
                author = this@MergePreviewTest.author
                committer = this@MergePreviewTest.author
                message = "fixture"
            })
            inserter.flush()
            id
        }
    }

    @Test
    fun `ancestor tips need no object writes in either direction`() {
        val base = commit(mapOf("file.txt" to "base\n"))
        val head = commit(mapOf("file.txt" to "changed\n"), base)
        repository(readOnly = true).use { repo ->
            assertTrue(MergeExecutor.checkMergeability(repo, head, base).success)
            assertTrue(MergeExecutor.checkMergeability(repo, base, head).success)
            assertTrue(MergeExecutor.checkMergeability(repo, head, head).success)
        }
    }

    @Test
    fun `diverged clean merge keeps combined blobs and trees temporary`() {
        val base = commit(mapOf("file.txt" to "one\ntwo\nthree\n"))
        val source = commit(mapOf("file.txt" to "ONE\ntwo\nthree\n"), base)
        val target = commit(mapOf("file.txt" to "one\ntwo\nTHREE\n"), base)
        val packs = storage.packs.keys.toSet()
        val files = storage.files.keys.toSet()
        repeat(2) {
            repository(readOnly = true).use { repo ->
                val result = MergeExecutor.checkMergeability(repo, source, target)
                assertTrue(result.success)
                assertTrue(result.conflictingFiles.isEmpty())
                val mergedBlob = repo.newObjectInserter().use { it.idFor(Constants.OBJ_BLOB, "ONE\ntwo\nTHREE\n".toByteArray()) }
                assertFalse(repo.objectDatabase.has(mergedBlob))
            }
        }
        assertEquals(packs, storage.packs.keys)
        assertEquals(files, storage.files.keys)
    }

    @Test
    fun `conflicts remain visible without storage writes`() {
        val base = commit(mapOf("file.txt" to "base\n", "clean.txt" to "base\n"))
        val source = commit(mapOf("file.txt" to "source\n", "clean.txt" to "source\n"), base)
        val target = commit(mapOf("file.txt" to "target\n", "clean.txt" to "base\n"), base)
        repository(readOnly = true).use { repo ->
            val result = MergeExecutor.checkMergeability(repo, source, target)
            assertFalse(result.success)
            assertEquals(listOf("file.txt"), result.conflictingFiles)
        }
    }

    @Test
    fun `recursive merge can read a temporary merge base`() {
        val base = commit(mapOf("a.txt" to "base\n", "b.txt" to "base\n"))
        val left = commit(mapOf("a.txt" to "left\n", "b.txt" to "base\n"), base)
        val right = commit(mapOf("a.txt" to "base\n", "b.txt" to "right\n"), base)
        val source = commit(mapOf("a.txt" to "left\n", "b.txt" to "right\n", "source.txt" to "source\n"), left, right)
        val target = commit(mapOf("a.txt" to "left\n", "b.txt" to "right\n", "target.txt" to "target\n"), right, left)
        repository(readOnly = true).use { repo ->
            assertTrue(MergeExecutor.checkMergeability(repo, source, target).success)
        }
    }
}
