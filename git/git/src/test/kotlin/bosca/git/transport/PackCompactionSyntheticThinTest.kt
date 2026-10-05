package bosca.git.transport

import bosca.git.TestDfsRefAdapter
import bosca.git.TestDfsStorageAdapter
import bosca.git.dfs.BoscaDfsObjDatabase
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the thin-pack replacement path of [compactNewlyReceivedPacks] with a
 * SYNTHETIC thin pack: a valid pack whose stored `.pack` header object count is
 * rewritten to disagree with its `.idx` count — the signature JGit's
 * DfsPackParser leaves behind when it thickens a thin pack without rewriting the
 * header. (The disk-fixture variant in ThinPackGcRepairTest is environment-gated;
 * this test always runs.)
 */
class PackCompactionSyntheticThinTest {

    private val repositoryId = UUID.random()
    private val storageAdapter = TestDfsStorageAdapter()
    private val refAdapter = TestDfsRefAdapter()

    private fun openRepo(): BoscaDfsRepository = BoscaDfsRepositoryBuilder().apply {
        repositoryId = this@PackCompactionSyntheticThinTest.repositoryId
        storageAdapter = this@PackCompactionSyntheticThinTest.storageAdapter
        refAdapter = this@PackCompactionSyntheticThinTest.refAdapter
        repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
    }.build()

    /** Commits blob+tree+commit (3 objects) and points refs/heads/main at it. */
    private fun seed() {
        val repo = openRepo()
        val ins = repo.objectDatabase.newInserter()
        val author = PersonIdent("T", "t@x")
        val blob = ins.insert(Constants.OBJ_BLOB, "content\n".toByteArray())
        val treeId = ins.insert(TreeFormatter().apply { append("f.txt", FileMode.REGULAR_FILE, blob) })
        val commit = ins.insert(CommitBuilder().apply {
            setTreeId(treeId); setAuthor(author); setCommitter(author); setMessage("c")
        })
        ins.flush()
        repo.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(commit); update() }
        repo.close()
    }

    /** Rewrites the 4-byte object count at offset 8 of every stored `.pack`. */
    private fun corruptHeaderCounts(count: Int) {
        for ((path, bytes) in storageAdapter.files) {
            if (!path.endsWith(".pack")) continue
            val mutated = bytes.copyOf()
            mutated[8] = (count ushr 24).toByte()
            mutated[9] = (count ushr 16).toByte()
            mutated[10] = (count ushr 8).toByte()
            mutated[11] = count.toByte()
            storageAdapter.files[path] = mutated
        }
    }

    @Test
    fun `header-mismatched packs are detected as thin and replaced`() {
        seed()
        corruptHeaderCounts(count = 999)
        // The in-memory adapter doesn't persist objectCount on commit; the thin
        // check compares the idx-side count, so backfill it (3 objects).
        storageAdapter.packs.values.forEach { it.objectCount = 3L }

        // A fresh instance lists packs from the (mutated) adapter — the same way
        // another pod would see a thin pack left behind by an earlier push.
        val repo = openRepo()
        val objdb = repo.objectDatabase as BoscaDfsObjDatabase

        val thinBefore = repo.objectDatabase.packs.count { objdb.isThinPack(it.packDescription.packName) }
        assertTrue(thinBefore > 0, "synthetic thin pack should be detected")

        compactNewlyReceivedPacks(repo, namesBefore = emptySet())
        repo.close()

        val fresh = openRepo()
        val freshDb = fresh.objectDatabase as BoscaDfsObjDatabase
        val thinAfter = fresh.objectDatabase.packs.count { freshDb.isThinPack(it.packDescription.packName) }
        assertEquals(0, thinAfter, "compaction should replace every thin pack")
        fresh.close()
    }

    @Test
    fun `a compacted pack is self-contained and not re-replaced`() {
        seed()
        // Note: DFS INSERT packs keep a 0 header count, so even a fresh pack
        // matches the thin signature once idx counts are known — which is why
        // production compacts after every receive. One pass replaces it...
        storageAdapter.packs.values.forEach { it.objectCount = 3L }
        openRepo().use { compactNewlyReceivedPacks(it, namesBefore = emptySet()) }

        // ...and the replacement (written by PackWriter with a correct header)
        // is stable: a second pass finds nothing thin and changes nothing.
        val repo = openRepo()
        val objdb = repo.objectDatabase as BoscaDfsObjDatabase
        val packsBefore = repo.objectDatabase.packs.map { it.packDescription.packName }
        assertFalse(packsBefore.any { objdb.isThinPack(it) })

        compactNewlyReceivedPacks(repo, namesBefore = emptySet())

        assertEquals(packsBefore, repo.objectDatabase.packs.map { it.packDescription.packName })
        repo.close()
    }

    @Test
    fun `non-DFS object databases are ignored`() {
        val plain = org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(DfsRepositoryDescription("plain"))
        val ins = plain.objectDatabase.newInserter()
        ins.insert(Constants.OBJ_BLOB, "x".toByteArray())
        ins.flush()
        // Every pack is "new", but the objdb is not Bosca's -> early return, no throw.
        compactNewlyReceivedPacks(plain, namesBefore = emptySet())
        plain.close()
    }

    @Test
    fun `a compactor failure is logged and swallowed`() {
        seed()
        corruptHeaderCounts(count = 999)
        storageAdapter.packs.values.forEach { it.objectCount = 3L }
        // Truncate the pack body so the compactor cannot read it.
        for ((path, bytes) in storageAdapter.files) {
            if (path.endsWith(".pack")) storageAdapter.files[path] = bytes.copyOf(16)
        }

        val repo = openRepo()
        // Must not throw: the push has already been acknowledged upstream.
        compactNewlyReceivedPacks(repo, namesBefore = emptySet())
        repo.close()
    }
}
