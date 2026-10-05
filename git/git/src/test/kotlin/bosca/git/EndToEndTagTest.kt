package bosca.git

import bosca.git.dfs.BoscaDfsRepository
import bosca.git.dfs.BoscaDfsRepositoryBuilder
import bosca.git.dfs.DfsPackExtensionInfo
import bosca.git.dfs.DfsPackInfo
import bosca.git.dfs.DfsRefAdapter
import bosca.git.dfs.DfsRefInfo
import bosca.git.dfs.DfsStorageAdapter
import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for tag operations on the Bosca DFS layer.
 * Tags mark release points — losing a tag means losing the pointer
 * to a known-good version of the code.
 */
class EndToEndTagTest {

    private val repositoryId = UUID.random()
    private lateinit var storageAdapter: TestDfsStorageAdapter
    private lateinit var refAdapter: TestDfsRefAdapter
    private val author = PersonIdent("Tagger", "tag@bosca.io")

    @BeforeTest
    fun setup() {
        storageAdapter = TestDfsStorageAdapter()
        refAdapter = TestDfsRefAdapter()
    }

    private fun openRepo(): BoscaDfsRepository {
        return BoscaDfsRepositoryBuilder().apply {
            this.repositoryId = this@EndToEndTagTest.repositoryId
            this.storageAdapter = this@EndToEndTagTest.storageAdapter
            this.refAdapter = this@EndToEndTagTest.refAdapter
            repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
        }.build()
    }

    @Test
    fun `lightweight tag points to correct commit`() {
        val repo = openRepo()
        val commitId = createTestCommit(repo, mapOf("release.txt" to "v1.0 release\n"), "Release v1.0")

        val tagRef = repo.refDatabase.newUpdate("refs/tags/v1.0", true)
        tagRef.setNewObjectId(commitId)
        tagRef.update()

        val freshRepo = openRepo()
        val resolved = freshRepo.refDatabase.findRef("refs/tags/v1.0")
        assertNotNull(resolved, "Tag must exist")
        assertEquals(commitId, resolved.objectId, "Tag must point to the correct commit")

        val files = readAllFiles(freshRepo, resolved.objectId)
        assertEquals("v1.0 release\n", files["release.txt"], "Tagged commit content must be accessible")
    }

    @Test
    fun `annotated tag preserves tagger and message`() {
        val repo = openRepo()
        val commitId = createTestCommit(repo, mapOf("app.kt" to "fun main() {}\n"), "Production release")

        val inserter = repo.objectDatabase.newInserter()
        val tag = TagBuilder()
        tag.setObjectId(commitId, Constants.OBJ_COMMIT)
        tag.tag = "v2.0"
        tag.tagger = author
        tag.message = "Production release v2.0\n\nSigned off by: release-bot"
        val tagId = inserter.insert(tag)
        inserter.flush()

        val tagRef = repo.refDatabase.newUpdate("refs/tags/v2.0", true)
        tagRef.setNewObjectId(tagId)
        tagRef.update()

        val freshRepo = openRepo()
        val resolved = freshRepo.refDatabase.findRef("refs/tags/v2.0")
        assertNotNull(resolved, "Annotated tag ref must exist")

        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(tagId), "Tag object must be readable")
        val tagObj = reader.open(tagId)
        assertEquals(Constants.OBJ_TAG, tagObj.type, "Object must be a tag type")

        val revWalk = RevWalk(freshRepo)
        val revTag = revWalk.parseTag(tagId)
        assertEquals("v2.0", revTag.tagName)
        assertEquals("Production release v2.0\n\nSigned off by: release-bot", revTag.fullMessage)
        assertEquals(author.name, revTag.taggerIdent.name)

        val taggedCommit = revWalk.parseCommit(revTag.`object`)
        val files = readAllFiles(freshRepo, taggedCommit.id)
        assertEquals("fun main() {}\n", files["app.kt"], "Content at tagged commit must be readable")

        reader.close()
        revWalk.dispose()
    }

    @Test
    fun `multiple tags on same commit are independent`() {
        val repo = openRepo()
        val commitId = createTestCommit(repo, mapOf("code.txt" to "stable code\n"), "Stable release")

        val tag1 = repo.refDatabase.newUpdate("refs/tags/v1.0", true)
        tag1.setNewObjectId(commitId)
        tag1.update()

        val tag2 = repo.refDatabase.newUpdate("refs/tags/stable", true)
        tag2.setNewObjectId(commitId)
        tag2.update()

        val tag3 = repo.refDatabase.newUpdate("refs/tags/latest", true)
        tag3.setNewObjectId(commitId)
        tag3.update()

        val freshRepo = openRepo()
        val v1 = freshRepo.refDatabase.findRef("refs/tags/v1.0")
        val stable = freshRepo.refDatabase.findRef("refs/tags/stable")
        val latest = freshRepo.refDatabase.findRef("refs/tags/latest")

        assertNotNull(v1)
        assertNotNull(stable)
        assertNotNull(latest)
        assertEquals(commitId, v1.objectId)
        assertEquals(commitId, stable.objectId)
        assertEquals(commitId, latest.objectId)
    }

    @Test
    fun `tags on different commits point to correct content`() {
        val repo = openRepo()
        val commit1 = createTestCommit(repo, mapOf("version.txt" to "1.0\n"), "v1.0")
        val commit2 = createTestCommit(repo, mapOf("version.txt" to "2.0\n"), "v2.0", parent = commit1)

        val tag1 = repo.refDatabase.newUpdate("refs/tags/v1.0", true)
        tag1.setNewObjectId(commit1)
        tag1.update()

        val tag2 = repo.refDatabase.newUpdate("refs/tags/v2.0", true)
        tag2.setNewObjectId(commit2)
        tag2.update()

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(commit2)
        mainRef.update()

        val freshRepo = openRepo()
        val v1Files = readAllFiles(freshRepo, freshRepo.refDatabase.findRef("refs/tags/v1.0")!!.objectId)
        assertEquals("1.0\n", v1Files["version.txt"], "v1.0 tag must point to v1 content")

        val v2Files = readAllFiles(freshRepo, freshRepo.refDatabase.findRef("refs/tags/v2.0")!!.objectId)
        assertEquals("2.0\n", v2Files["version.txt"], "v2.0 tag must point to v2 content")
    }

    @Test
    fun `tag survives across repository instances`() {
        val repo = openRepo()
        val commitId = createTestCommit(repo, mapOf("critical.txt" to "do not lose this\n"), "Tagged release")

        val tagRef = repo.refDatabase.newUpdate("refs/tags/critical-release", true)
        tagRef.setNewObjectId(commitId)
        tagRef.update()
        repo.close()

        val instance2 = openRepo()
        val tag2 = instance2.refDatabase.findRef("refs/tags/critical-release")
        assertNotNull(tag2, "Tag must survive instance close/reopen")
        assertEquals(commitId, tag2.objectId)
        instance2.close()

        val instance3 = openRepo()
        val files = readAllFiles(instance3, commitId)
        assertEquals("do not lose this\n", files["critical.txt"])
        instance3.close()
    }

    @Test
    fun `tag deletion removes ref but preserves commit`() {
        val repo = openRepo()
        val commitId = createTestCommit(repo, mapOf("file.txt" to "content\n"), "tagged commit")

        val mainRef = repo.refDatabase.newUpdate("refs/heads/main", true)
        mainRef.setNewObjectId(commitId)
        mainRef.update()

        val tagRef = repo.refDatabase.newUpdate("refs/tags/temp", true)
        tagRef.setNewObjectId(commitId)
        tagRef.update()

        val deleteRef = repo.refDatabase.newUpdate("refs/tags/temp", false)
        deleteRef.setExpectedOldObjectId(commitId)
        deleteRef.setForceUpdate(true)
        deleteRef.delete()

        val freshRepo = openRepo()
        val deletedTag = freshRepo.refDatabase.findRef("refs/tags/temp")
        assertTrue(deletedTag == null || deletedTag.objectId == null, "Deleted tag must not resolve")

        val reader = freshRepo.objectDatabase.newReader()
        assertTrue(reader.has(commitId), "Commit must still exist after tag deletion (reachable from main)")
        reader.close()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createTestCommit(
        repo: BoscaDfsRepository,
        files: Map<String, String>,
        message: String,
        parent: ObjectId? = null
    ): ObjectId {
        val inserter = repo.objectDatabase.newInserter()
        val tf = TreeFormatter()
        for ((name, content) in files.toSortedMap()) {
            tf.append(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
        }
        val treeId = inserter.insert(tf)
        val commit = CommitBuilder().apply {
            setTreeId(treeId)
            this.author = this@EndToEndTagTest.author
            this.committer = this@EndToEndTagTest.author
            this.message = message
            if (parent != null) setParentId(parent)
        }
        val commitId = inserter.insert(commit)
        inserter.flush()
        return commitId
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
