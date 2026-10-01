package bosca.git.dfs

import bosca.git.TestDfsRefAdapter
import bosca.git.TestDfsStorageAdapter
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [BoscaDfsRefDatabase]'s symbolic-ref resolution arms (resolved and
 * dangling targets), the reflog-less contract, and compare-and-remove guards.
 */
class BoscaDfsRefDatabaseSymbolicTest {

    private val repositoryId = UUID.random()
    private val storageAdapter = TestDfsStorageAdapter()
    private val refAdapter = TestDfsRefAdapter()
    private lateinit var repo: BoscaDfsRepository
    private lateinit var commitId: ObjectId

    @BeforeTest
    fun setup() {
        repo = BoscaDfsRepositoryBuilder().apply {
            repositoryId = this@BoscaDfsRefDatabaseSymbolicTest.repositoryId
            storageAdapter = this@BoscaDfsRefDatabaseSymbolicTest.storageAdapter
            refAdapter = this@BoscaDfsRefDatabaseSymbolicTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()

        val ins = repo.objectDatabase.newInserter()
        val person = PersonIdent("T", "t@x")
        val blob = ins.insert(Constants.OBJ_BLOB, "x".toByteArray())
        val treeId = ins.insert(TreeFormatter().apply { append("f.txt", FileMode.REGULAR_FILE, blob) })
        commitId = ins.insert(CommitBuilder().apply {
            setTreeId(treeId); setAuthor(person); setCommitter(person); setMessage("c")
        })
        ins.flush()
        repo.refDatabase.newUpdate("refs/heads/main", true).apply { setNewObjectId(commitId); update() }
    }

    @AfterTest
    fun teardown() = repo.close()

    @Test
    fun `symbolic refs resolve their target when present`() {
        repo.refDatabase.newUpdate(Constants.HEAD, true).link("refs/heads/main")

        val head = repo.refDatabase.findRef(Constants.HEAD)
        assertTrue(head?.isSymbolic == true)
        assertEquals("refs/heads/main", head.target.name)
        assertEquals(commitId, head.target.objectId)
    }

    @Test
    fun `dangling symbolic refs surface as unborn targets`() {
        // A symbolic ref whose target branch does not exist (an unborn HEAD).
        refAdapter.compareAndPut(repositoryId, Constants.HEAD, null, ObjectId.zeroId().name(), null, "refs/heads/unborn")
        repo.refDatabase.refresh() // the direct adapter write bypassed the ref cache

        // getRefs() excludes HEAD by JGit contract; resolve it directly.
        val head = repo.refDatabase.findRef(Constants.HEAD)
        assertTrue(head?.isSymbolic == true)
        assertEquals("refs/heads/unborn", head!!.target.name)
        assertEquals(Ref.Storage.NEW, head.target.storage)
        assertNull(head.target.objectId)
    }

    @Test
    fun `reflogs are not supported`() {
        val main = repo.refDatabase.findRef("refs/heads/main")
        assertNull(repo.refDatabase.getReflogReader(main!!))
    }

    @Test
    fun `deleting a branch removes it and repeats are refused`() {
        val update = repo.refDatabase.newUpdate("refs/heads/main", true)
        update.setForceUpdate(true)
        update.delete()
        assertNull(repo.refDatabase.findRef("refs/heads/main"))

        // Removing again finds nothing to compare against.
        val again = repo.refDatabase.newUpdate("refs/heads/main", true)
        again.setForceUpdate(true)
        assertFalse(again.delete() == org.eclipse.jgit.lib.RefUpdate.Result.FORCED)
    }
}
